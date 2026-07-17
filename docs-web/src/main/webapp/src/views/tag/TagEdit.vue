<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { listTags, getTag, getTagStats, updateTag, deleteTag } from '../../api/tag'
import type { AclEntry } from '../../api/acl'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import AclEditor from '../../components/AclEditor.vue'

const props = defineProps<{ id: string }>()
const { t } = useI18n()
const router = useRouter()
const toast = useToast()
const confirm = useConfirm()
const { confirmDanger } = useConfirmDanger()
const queryClient = useQueryClient()

const name = ref('')
const color = ref('2aabd2')
const parent = ref<string | null>(null)

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => listTags().then((r) => r.data.tags),
  staleTime: 60_000,
})

// Tag detail carries the direct ACLs, the caller's writability, and the creator (whose
// base grants are immutable). Refetched after every ACL change so the list stays live.
const { data: detail, refetch: refetchDetail } = useQuery({
  queryKey: computed(() => ['tag', props.id]),
  queryFn: () => getTag(props.id).then((r) => r.data),
})

// Per-tag document counts — the source for the READ grant-disclosure count.
const { data: tagStats } = useQuery({
  queryKey: ['tag-stats'],
  queryFn: () => getTagStats().then((r) => r.data.stats),
  staleTime: 60_000,
})

const tagAcls = computed<AclEntry[]>(() => detail.value?.acls ?? [])
const tagWritable = computed(() => detail.value?.writable ?? false)
const docCount = computed(() => tagStats.value?.[props.id] ?? 0)

// Distinct WRITE holders currently on the tag (by target id). The server refuses to remove
// the final one; the client mirrors that so the UI never offers a doomed delete.
const distinctWriteHolders = computed(
  () => new Set(tagAcls.value.filter((a) => a.perm === 'WRITE').map((a) => a.id)).size,
)

// Which rows the editor must render as non-removable:
//   - the tag creator's own base READ/WRITE grants (mandatory, backend-protected), and
//   - the sole remaining WRITE holder's row — even a NON-creator one, which arises when the
//     creator's account is deleted (UserDao.delete soft-deletes their ACLs, leaving a
//     non-creator sole owner). Without this the server's last-write guard would reject a
//     delete the UI had offered — the exact guaranteed-fail toast #88 removes.
// A string result gives the lock marker a reason-specific label.
function isOwnerBaseAcl(acl: AclEntry): boolean | string {
  if (acl.type === 'USER' && !!detail.value && acl.name === detail.value.creator) return true
  if (acl.perm === 'WRITE' && distinctWriteHolders.value === 1) return t('ui.tag_acl.last_owner_locked')
  return false
}

// Granting READ on a tag reveals every document carrying it (now and later). Surface that
// inheritance with the current document count before the grant lands. WRITE (co-owner)
// grants are a deliberate ownership action and need no disclosure.
function confirmGrant(perm: 'READ' | 'WRITE'): Promise<boolean> {
  if (perm !== 'READ') return Promise.resolve(true)
  return new Promise((resolve) => {
    confirm.require({
      header: t('ui.tag_acl.disclose_header'),
      message: t('ui.tag_acl.disclose_message', { count: docCount.value }),
      icon: 'pi pi-eye',
      rejectProps: { severity: 'secondary', outlined: true },
      accept: () => resolve(true),
      reject: () => resolve(false),
      onHide: () => resolve(false),
    })
  })
}

function getDescendantIds(tagId: string, allTags: Array<{ id: string; parent: string | null }>): Set<string> {
  const ids = new Set<string>()
  const queue = [tagId]
  while (queue.length) {
    const current = queue.pop()!
    for (const tag of allTags) {
      if (tag.parent === current && !ids.has(tag.id)) {
        ids.add(tag.id)
        queue.push(tag.id)
      }
    }
  }
  return ids
}

const parentOptions = computed(() => {
  const allTags = tags.value ?? []
  const excluded = getDescendantIds(props.id, allTags)
  excluded.add(props.id)
  return [
    { label: t('ui.tags_page.none_root'), value: null },
    ...allTags
      .filter((tag) => !excluded.has(tag.id))
      .map((tag) => ({ label: tag.name, value: tag.id })),
  ]
})

function loadFromCache() {
  const tag = tags.value?.find((item) => item.id === props.id)
  if (tag) {
    name.value = tag.name
    color.value = tag.color.replace('#', '')
    parent.value = tag.parent
  }
}

watch([tags, () => props.id], loadFromCache, { immediate: true })

const { mutate: save, isPending: loading } = useMutation({
  mutationFn: () => updateTag(props.id, name.value, '#' + color.value, parent.value ?? undefined),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['tags'] })
    toast.add({ severity: 'success', summary: t('ui.tag_edit.tag_updated'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.tag_edit.failed_update'), life: 3000 })
  },
})

function handleDelete() {
  confirmDanger({
    message: t('ui.tag_edit.delete_confirm', { name: name.value }),
    header: t('ui.tag_edit.delete_tag'),
    accept: () => {
      deleteTag(props.id).then(() => {
        queryClient.invalidateQueries({ queryKey: ['tags'] })
        toast.add({ severity: 'success', summary: t('ui.tag_edit.tag_deleted'), life: 2000 })
        router.push({ name: 'tags' })
      }).catch(() => {
        toast.add({ severity: 'error', summary: t('ui.tag_edit.failed_delete'), life: 3000 })
      })
    },
  })
}
</script>

<template>
  <div class="tag-edit-page">
    <div class="page-header">
      <h1>{{ t('ui.tag_edit.title') }}</h1>
      <router-link :to="{ name: 'tags' }" class="back-link">
        <i class="pi pi-arrow-left" /> {{ t('ui.back_to_tags') }}
      </router-link>
    </div>

    <Card style="max-width: 480px">
      <template #content>
        <div class="form-field">
          <label for="tag-name">{{ t('ui.tag_edit.name') }}</label>
          <InputText id="tag-name" v-model="name" class="w-full" />
        </div>
        <div class="form-field">
          <label id="tag-color-label">{{ t('ui.tag_edit.color') }}</label>
          <div class="color-row">
            <ColorPicker v-model="color" aria-labelledby="tag-color-label" />
            <span class="color-preview" :style="{ background: '#' + color }">{{ name || t('ui.tag_edit.preview') }}</span>
          </div>
        </div>
        <div class="form-field">
          <label for="tag-parent">{{ t('ui.tag_edit.parent') }}</label>
          <Select
            v-model="parent"
            inputId="tag-parent"
            :options="parentOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
            showClear
            filter
            :placeholder="t('ui.tag_edit.no_parent')"
          />
        </div>
        <div class="flex gap-2 mt-4">
          <Button :label="t('save')" icon="pi pi-check" :loading="loading" @click="save()" />
          <Button :label="t('delete')" icon="pi pi-trash" severity="danger" outlined @click="handleDelete" />
        </div>
      </template>
    </Card>

    <Card style="max-width: 480px" class="acl-card">
      <template #content>
        <h2 class="acl-heading">{{ t('ui.tag_acl.title') }}</h2>
        <p class="acl-desc">{{ t('ui.tag_acl.description') }}</p>
        <AclEditor
          :source-id="props.id"
          :acls="tagAcls"
          :writable="tagWritable"
          :immutable="isOwnerBaseAcl"
          :before-add="confirmGrant"
          @changed="refetchDetail"
        />
      </template>
    </Card>
  </div>
</template>

<style scoped>
.tag-edit-page {
  padding: 1.5rem;
  max-width: 600px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.25rem;
}
.page-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}

.back-link {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  text-decoration: none;
}
.back-link:hover {
  color: var(--p-primary-color);
  text-decoration: none;
}

.form-field {
  margin-bottom: 1rem;
}
.form-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-color);
}

.color-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.color-preview {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.75rem;
  border-radius: 4px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--teedy-tag-text);
}

.acl-card {
  margin-top: 1.25rem;
}
.acl-heading {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}
.acl-desc {
  margin: 0.25rem 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
