/**
 * Creating a tag together with the permissions chosen before it existed — ONE implementation
 * with TWO hosts.
 *
 * A tag's grants can only be sent to an id, and a tag being created has none yet. So the
 * permissions section runs in AclEditor's `deferred` mode: every add and remove is collected
 * here, and the collection is PUT to /acl the moment PUT /tag hands back an id.
 *
 * It was extracted from components/TagCreatePanel.vue (#288), which owned the only copy, when
 * the tag management page needed the same thing (#306: "let permissions be set directly in the
 * tag-management CREATE flow"). The hosts are now:
 *   - components/TagCreatePanel.vue — the document editor's create-tag side panel
 *   - views/tag/TagList.vue         — the tag management page's create card
 * A second copy would have meant every later change to the create contract — the ordering, the
 * in-flight snapshot, the partial-failure rule — landing on one surface only.
 *
 * The hosts keep what genuinely differs: their own name/colour/parent fields, what they do with
 * the created tag (a panel hands it to a document's selection; the page clears its draft), and
 * how they report success.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createTag } from '../api/tag'
import { addAcl, type AclEntry } from '../api/acl'
import type { TagFormAcl } from '../components/TagForm.vue'
import { useAuthStore } from '../stores/auth'

/** The seed colour a new tag starts from, so a tag looks the same whichever surface made it. */
export const DEFAULT_TAG_COLOR = '2aabd2'

/** The fields the host owns, snapshotted by the host before it calls {@link TagCreate.create}. */
export interface TagCreateDraft {
  name: string
  /** Hex colour WITH the leading '#', the form the tag endpoints take. */
  color: string
  parent: string | null
  /** The tag's icon (#287): the stored reference, or null for none. */
  icon: string | null
}

export interface TagCreateOutcome {
  id: string
  /**
   * At least one grant was refused. The tag itself exists — it is NOT rolled back, because it
   * is already in use (on a document, in the tree) by the time this is read, and the
   * permissions can be finished on the tag's own page.
   */
  grantFailed: boolean
}

interface ApiError {
  response?: { data?: { message?: string } }
}

export function useTagCreate() {
  const { t } = useI18n()
  const auth = useAuthStore()

  /** Grants collected while the tag has no id, applied only once it has one. */
  const pendingAcls = ref<AclEntry[]>([])
  const errorMessage = ref<string | null>(null)

  // The base grants the server will create for the creator (TagCreationUtil gives the owner
  // READ and WRITE). Showing the owner row makes the permissions section read as it will after
  // the save, rather than as an empty list; it is locked because those grants are the backend's
  // and it refuses to remove them.
  const ownerAcl = computed<AclEntry | null>(() =>
    auth.username
      ? { perm: 'WRITE', id: auth.username, name: auth.username, type: 'USER' }
      : null,
  )

  function isOwnerRow(acl: AclEntry): boolean {
    return acl.type === 'USER' && acl.perm === 'WRITE' && acl.id === auth.username
  }

  const entries = computed<AclEntry[]>(() =>
    ownerAcl.value ? [ownerAcl.value, ...pendingAcls.value] : [...pendingAcls.value],
  )

  /** Everything TagForm's permissions section needs while the tag does not exist yet. */
  const aclState = computed<TagFormAcl>(() => ({
    // No id exists yet — deferred mode never reads it.
    sourceId: '',
    entries: entries.value,
    writable: true,
    immutable: (acl) => isOwnerRow(acl),
    deferred: true,
  }))

  function addGrant(grant: AclEntry) {
    // The owner's grants are the server's to create, and a grant already collected is already
    // collected — either would just be a round trip that changes nothing.
    if (isOwnerRow(grant)) return
    if (pendingAcls.value.some((a) => a.perm === grant.perm && a.id === grant.id)) return
    pendingAcls.value = [...pendingAcls.value, grant]
  }

  function removeGrant(grant: AclEntry) {
    pendingAcls.value = pendingAcls.value.filter(
      (a) => !(a.perm === grant.perm && a.id === grant.id),
    )
  }

  /** Back to a blank draft: nothing collected, nothing failed. */
  function reset() {
    pendingAcls.value = []
    errorMessage.value = null
  }

  /**
   * Create the tag, then apply the collected grants to it.
   *
   * Returns the outcome, or null when the CREATE itself failed — `errorMessage` then carries
   * the server's own reason, which the host shows beside the field it belongs to.
   *
   * The grants are SNAPSHOTTED before the first await, and the host is expected to snapshot
   * its own fields into `draft` the same way: every one of them is a ref the user can still
   * edit while the request is in flight, and re-reading one after an await would apply a
   * later draft's value to the tag this call created.
   *
   * IN-FLIGHT STATE IS THE HOST'S, deliberately: a host's save does not end when this call
   * returns. The panel still has to refresh the tag cache, report, hand the tag to the
   * document's selection and close itself, and the affordances it withdraws for the duration
   * (its close icon, its Cancel) must stay withdrawn across ALL of that — a flag owned here
   * would be released while the tail was still running and let the panel be closed on a tag it
   * had not handed over yet. So each host wraps its WHOLE save in its own `saving` ref, which
   * is also what guards this call against being entered twice.
   */
  async function create(draft: TagCreateDraft): Promise<TagCreateOutcome | null> {
    const grants = [...pendingAcls.value]

    errorMessage.value = null
    try {
      const { data } = await createTag(draft.name, draft.color, draft.parent ?? undefined, draft.icon)

      // Now, and only now, the grants have somewhere to land. A grant that fails does NOT undo
      // the tag — it exists — so it is reported and the flow continues.
      let grantFailed = false
      for (const grant of grants) {
        try {
          await addAcl(data.id, grant.perm, grant.name ?? '', grant.type)
        } catch {
          grantFailed = true
        }
      }
      return { id: data.id, grantFailed }
    } catch (error) {
      // The tag endpoints answer with a named client error (IllegalTagName, ValidationError,
      // ParentNotFound, and the duplicate-name case). Quoting it is the difference between
      // "fix the name" and "try again"; a toast would be gone by the time the name is retyped.
      errorMessage.value =
        (error as ApiError).response?.data?.message || t('ui.tags_page.failed_create_tag')
      return null
    }
  }

  return { pendingAcls, errorMessage, aclState, addGrant, removeGrant, reset, create }
}
