<script setup lang="ts">
/**
 * The tag form — name, colour, parent, and the permissions section — as ONE implementation
 * with TWO hosts (#288).
 *
 * It was extracted verbatim from TagEdit.vue, which owned the only copy: the reporter asked
 * for "the same editor which is already present" inside the document edit view, and a second
 * form would have meant every later tag-form change landing on one surface only. The hosts are
 * now:
 *   - views/tag/TagEdit.vue        — editing an existing tag on the tag management page
 *   - components/TagCreatePanel.vue — creating one from the document editor's side panel
 *
 * Contract notes:
 * - The host owns the VALUES (`v-model:name` / `:color` / `:parent`) and the ACL state. This
 *   component renders and reports; it fetches and saves nothing, because the two hosts save
 *   through completely different calls (POST /tag/{id} vs PUT /tag + deferred grants).
 * - `color` is the hex WITHOUT the leading '#', which is what PrimeVue's ColorPicker binds and
 *   what both hosts have always stored.
 * - `icon` (#287) is the STORED reference — `emoji:<grapheme>` or `set:<iconId>` — or null, and
 *   is passed through to the server unchanged. The field reports null while a half-typed emoji is
 *   in the box, so a Save mid-typing stores no icon rather than something the server would refuse.
 * - `idPrefix` prefixes every field id. `tag-name`/`tag-color-label`/`tag-parent` are e2e
 *   selectors on the management page (e2e/tags.spec.ts), so that host keeps `tag`; the panel
 *   takes its own prefix, which is also what stops the two forms colliding on ids should they
 *   ever share a page.
 * - `parentOptions` is the host's to build: the management page must exclude the tag itself
 *   and its descendants (a cycle), while a tag that does not exist yet has neither.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Card from 'primevue/card'
import Button from 'primevue/button'
import AclEditor from './AclEditor.vue'
import TagIconField from './TagIconField.vue'
import TagIconMark from './TagIconMark.vue'
import type { AclEntry, AclTarget } from '../api/acl'
import type { Tag } from '../api/tag'
import { matchTagsByName } from '../utils/tagSynonyms'

export interface TagFormParentOption {
  label: string
  value: string | null
}

/** Everything the permissions section needs, as one object rather than six forwarded props. */
export interface TagFormAcl {
  /** The ACL source id. Empty (and unread) while `deferred` is set — see AclEditor. */
  sourceId: string
  entries: AclEntry[]
  writable: boolean
  immutable?: (acl: AclEntry) => boolean | string
  beforeAdd?: (perm: 'READ' | 'WRITE', target: AclTarget) => boolean | Promise<boolean>
  /** The tag does not exist yet: collect grants instead of sending them. */
  deferred?: boolean
}

const props = defineProps<{
  name: string
  /** Hex colour WITHOUT the leading '#'. */
  color: string
  /**
   * The tag's icon reference (#287) — `emoji:<grapheme>` or `set:<iconId>` — or nothing.
   * Optional: absent is what the API says for a tag with no icon, which is most of them.
   */
  icon?: string | null
  parent: string | null
  parentOptions: TagFormParentOption[]
  idPrefix: string
  acl: TagFormAcl
  /**
   * Placeholder for the Name field. Only the tag management page sets it: its create card has
   * always carried "Tag name" there, and six e2e specs seed their tags through that placeholder
   * (tags, tag-acl, bulk, search, saved-filters, settings-crud). Left off elsewhere, so the
   * edit page and the side panel render the labelled field exactly as before.
   */
  namePlaceholder?: string
  /**
   * Drop the card chrome. The management page has always shown the form as two cards and its
   * screenshots pin that; the side panel is already a surface of its own, so it renders flat.
   */
  flat?: boolean
  /** Max width of each card. The management page has always constrained the form to 480px. */
  maxWidth?: string
  /**
   * Put the caret in the Name field when the form appears. Only the side panel wants this —
   * the panel opens BECAUSE the user is naming a tag. The management page must not grab focus
   * on load, so it leaves this off. PrimeVue's Drawer focuses `[autofocus]` inside its content
   * ahead of anything else, which is the whole of the panel's focus handling.
   */
  autofocusName?: boolean
  /**
   * The tag's synonyms (#280). PRESENT (even as an empty array) is what makes the chips editor
   * render at all, which is how the section stays on the tag EDIT page only — the approved
   * design manages synonyms there, and the two CREATE hosts (the document editor's side panel,
   * the management page's create card) leave the prop off and render exactly as before.
   */
  synonyms?: string[]
  /**
   * Every tag the host knows about, WITH their synonyms — the source for the live "already in
   * use" hint the reporter asked for ("while typing the beginning of a word, we already could
   * visualize similar words"). Purely an early warning: the authority on a collision is the
   * server, which refuses the save and names the conflict. Omitted means no hint.
   */
  synonymTags?: Tag[]
  /** The tag being edited, excluded from that hint — its own names are not conflicts. */
  synonymTagId?: string
  /**
   * The synonyms the SERVER currently holds (TEEDY-154). Only these can be split off into a tag
   * of their own, because a split is a server action on a stored row: a word that has only been
   * typed into the form is not a synonym yet, and offering the action on it would promise a call
   * the server would refuse. Omitted means nothing is stored — which is what the two CREATE
   * hosts have, editing a tag that does not exist yet — and the action is then never offered.
   */
  storedSynonyms?: string[]
}>()

const emit = defineEmits<{
  'update:name': [value: string]
  'update:color': [value: string]
  'update:icon': [value: string | null]
  'update:parent': [value: string | null]
  /** The synonym chip list changed. */
  'update:synonyms': [value: string[]]
  /**
   * Split this synonym off into a tag of its own (TEEDY-154). Reported rather than performed:
   * it is a server call that removes a synonym from one tag and creates another, which no tag
   * write expresses, and this component saves nothing.
   */
  'split-synonym': [name: string]
  /** The persisted ACL list changed on the server; the host must re-read it. */
  'acl-changed': []
  /** Deferred mode: a grant to hold until the tag exists. */
  'acl-add': [acl: AclEntry]
  /** Deferred mode: a held grant to drop again. */
  'acl-remove': [acl: AclEntry]
}>()

const { t } = useI18n()

// --- The manual hex code beside the picker (#303) ---
//
// The swatch picker can only be POINTED AT, so a user holding a brand's hex code had no way to
// enter it. This field takes one and reports it in the picker's OWN canonical form: six
// LOWERCASE hex digits with no '#'. That form is not a preference — it is what PrimeVue's
// ColorPicker emits (`RGBtoHEX` joins `Number.prototype.toString(16)`, which is lowercase) and
// therefore what both hosts have stored since before this field existed. Emitting anything else
// would make a typed colour differ byte-for-byte from a picked one.
//
// A value the host already holds is left exactly as it is: an uppercase colour loaded from a tag
// written by an older client stays uppercase until someone actually edits it.

const HEX_SIX = /^[0-9a-fA-F]{6}$/
const HEX_THREE = /^[0-9a-fA-F]{3}$/
/** Hex digits, optionally behind a '#', but not yet enough of them to be a colour. */
const HEX_UNFINISHED = /^#?[0-9a-fA-F]{0,5}$/

/**
 * The colour a FINISHED code names — six hex digits, with or without the '#'. This is the only
 * form a keystroke is allowed to propagate, and the reason is '336'.
 *
 * '336' is a valid CSS shorthand AND the first three characters of '336699'. A rule that read
 * shorthand as the user typed could not tell those apart, so it propagated #333366 three
 * characters into typing a completely different colour — and a pause, a tab away or a Save at
 * that instant persisted it, with nothing on screen to say so.
 */
function completeHex(raw: string): string | null {
  const value = raw.trim().replace(/^#/, '')
  return HEX_SIX.test(value) ? value.toLowerCase() : null
}

/**
 * The colour a code names once the user has FINISHED with the field: a complete code, or the
 * three-digit CSS shorthand — which counts only WITH its '#', because '#f0a' is a code someone
 * wrote deliberately while 'F0A' is indistinguishable from a six-digit code left half-typed.
 */
function settledHex(raw: string): string | null {
  const complete = completeHex(raw)
  if (complete !== null) return complete
  const value = raw.trim()
  if (!value.startsWith('#')) return null
  const digits = value.slice(1)
  if (!HEX_THREE.test(digits)) return null
  return digits
    .split('')
    .map((digit) => digit + digit)
    .join('')
    .toLowerCase()
}

const hexText = ref('#' + props.color)
const hexInvalid = ref(false)

watch(
  () => props.color,
  (color) => {
    // The host answering our own emit is not a colour change — rewriting the box then would
    // retype '#FF00AA' as '#ff00aa' under the caret, mid-word. Anything else (the picker, a
    // host-side reset, a tag finishing loading) IS one, and the box follows it. Every emit
    // leaves a COMPLETE code in the box (blur settles a shorthand before emitting it), so this
    // is the whole of the echo test.
    if (completeHex(hexText.value) === color.toLowerCase()) return
    hexText.value = '#' + color
    hexInvalid.value = false
  },
)

// --- Synonym chips (#280) ---
//
// A synonym is a second name that finds this tag. The editor is a chip list plus one input,
// because that is what the approved design describes and because a synonym has no attributes of
// its own — there is nothing to edit about one, only to add or remove it.
//
// Everything here is a HINT. Whether a name may be used is a question about tags this account
// can read, which only the server can answer, and it answers it by refusing the save with an
// error that names the conflict. Warning while typing is what the reporter asked for on top of
// that; it must never be the thing that decides.

const synonymDraft = ref('')

/** How many similar names are worth listing before the hint stops being readable. */
const SIMILAR_LIMIT = 3

const synonymList = computed(() => props.synonyms ?? [])

/**
 * Case-insensitive equality for the hint.
 *
 * `toLowerCase`, NOT `toLocaleLowerCase`: the locale-aware fold maps ASCII "I" to the dotless
 * "ı" on a Turkish host, which would make the hint disagree with the server — whose comparison
 * (`equalsIgnoreCase`) folds per character and is locale-independent.
 */
function sameName(a: string, b: string): boolean {
  return a.toLowerCase() === b.toLowerCase()
}

/** Tags whose name or one of whose synonyms the draft resembles — the tag being edited aside. */
const draftMatches = computed(() => {
  const draft = synonymDraft.value.trim()
  if (!draft || !props.synonymTags) return []
  const others = props.synonymTags.filter((tag) => tag.id !== props.synonymTagId)
  return matchTagsByName(others, draft)
})

/** A name is TAKEN when it is exactly a visible tag's name or synonym. */
const draftTaken = computed(() =>
  draftMatches.value
    .filter(
      (match) =>
        sameName(match.tag.name, synonymDraft.value.trim()) ||
        (match.tag.synonyms ?? []).some((synonym) =>
          sameName(synonym, synonymDraft.value.trim()),
        ),
    )
    .map((match) => match.tag.name),
)

/** Already a chip on this tag, or the tag's own name — nothing to add either way. */
const draftOnThisTag = computed(() => {
  const draft = synonymDraft.value.trim()
  return (
    !!draft && (synonymList.value.some((s) => sameName(s, draft)) || sameName(props.name, draft))
  )
})

/** One line under the input: what the typed word already is, or what it looks like. */
const synonymNotice = computed<{ severity: 'warn' | 'info'; text: string } | null>(() => {
  const draft = synonymDraft.value.trim()
  if (!draft) return null
  if (draftOnThisTag.value) {
    return { severity: 'warn', text: t('ui.tag_edit.synonym_duplicate', { name: draft }) }
  }
  if (draftTaken.value.length) {
    return {
      severity: 'warn',
      text: t('ui.tag_edit.synonym_in_use', { name: draft, tags: draftTaken.value.join(', ') }),
    }
  }
  const similar = draftMatches.value
    .map((match) => (match.via ? `${match.tag.name} (${match.via})` : match.tag.name))
    .slice(0, SIMILAR_LIMIT)
  if (similar.length) {
    return { severity: 'info', text: t('ui.tag_edit.synonym_similar', { tags: similar.join(', ') }) }
  }
  return null
})

/**
 * Add the typed word as a chip.
 *
 * A word already on THIS tag is refused here rather than at save: the list would be unchanged,
 * so there is nothing for the server to reject and nothing for the user to see happen. A word
 * that collides with ANOTHER tag is deliberately allowed through — the notice above has already
 * said so, and the reporter's own shape for this is "if we then still forcively try to overcome,
 * the save button will say NO", with the server naming the conflict.
 */
function addSynonym() {
  const draft = synonymDraft.value.trim()
  if (!draft || draftOnThisTag.value) return
  emit('update:synonyms', [...synonymList.value, draft])
  synonymDraft.value = ''
}

function removeSynonym(synonym: string) {
  emit(
    'update:synonyms',
    synonymList.value.filter((value) => value !== synonym),
  )
}

// --- Making a synonym the main name (TEEDY-153) ---
//
// A tag's name and one of its synonyms trade places. It is deliberately NOT a call of its own:
// both values are already fields of this form, so the swap is two ordinary edits reported to the
// host, and the page's existing Save persists them in the ONE tag write the server already
// accepts (name plus the full synonym list). The tag therefore keeps its id, and with it its
// documents and its ACLs — nothing is re-tagged, and every word that resolved before resolves
// after, only from the other side of the pair.

/**
 * The swap this form is currently carrying, so the notice can name both words.
 *
 * It holds the pair AND the list it emitted, and the message lasts exactly as long as that state
 * does: any later edit — another chip, another name, the re-seed a save performs — leaves the
 * form somewhere else, and a sentence about the previous state would be describing nothing on
 * screen. The message itself says only what is true of the form both before and after a save, so
 * it can never contradict what has already been stored.
 */
const pendingSwap = ref<{ promoted: string; demoted: string; list: string[] } | null>(null)

function makeMainName(synonym: string) {
  const demoted = props.name.trim()
  // Whatever its spelling, the promoted word leaves the chips: a tag whose name is also its own
  // synonym is refused by the server, and the two would be indistinguishable to every search.
  const rest = synonymList.value.filter((value) => !sameName(value, synonym))
  // An empty Name field has nothing to demote — the swap is then simply "use this word" rather
  // than a trade, and adding a blank chip would be a synonym the user could never have typed.
  const demote = !!demoted && !rest.some((value) => sameName(value, demoted))
  const next = demote ? [demoted, ...rest] : rest
  pendingSwap.value = demoted ? { promoted: synonym, demoted, list: next } : null
  emit('update:synonyms', next)
  emit('update:name', synonym)
}

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index])
}

// Compared by CONTENT rather than by array identity: a host is free to copy what
// `update:synonyms` hands it — the re-seed after a save does exactly that — and the message has
// to survive its own change arriving back, then go when a different one does.
watch([() => props.synonyms, () => props.name], ([synonyms, name]) => {
  const pending = pendingSwap.value
  if (!pending) return
  if (name !== pending.promoted || !sameList(synonyms ?? [], pending.list)) {
    pendingSwap.value = null
  }
})

// --- Splitting a synonym off into its own tag (TEEDY-154) ---
//
// The other half of the swap, and the one this form cannot do: it removes a synonym from THIS
// tag and creates ANOTHER one, which no tag write expresses. So the chip only reports the ask
// and the host makes the call.

/**
 * The SERVER'S spelling of this word, or null when it holds no such word.
 *
 * Matched case-insensitively because a chip may have been removed and re-added in another case
 * without being saved — the server still holds the row, under the spelling it stored. That
 * spelling is what the split has to travel with: the confirmation names it, the call sends it
 * and the toast reports it, so all three say the word the tag will actually be called rather
 * than a casing that exists only in this form.
 */
function storedSpelling(synonym: string): string | null {
  return (props.storedSynonyms ?? []).find((value) => sameName(value, synonym)) ?? null
}

function isStored(synonym: string): boolean {
  return storedSpelling(synonym) !== null
}

function requestSplit(synonym: string) {
  const stored = storedSpelling(synonym)
  if (stored === null) return
  emit('split-synonym', stored)
}

function onHexInput(raw: string) {
  hexText.value = raw
  const complete = completeHex(raw)
  if (complete !== null) {
    hexInvalid.value = false
    emit('update:color', complete)
    return
  }
  // Six keystrokes make a colour, so a code that is merely UNFINISHED is not yet wrong —
  // complaining after the second character is noise rather than feedback. A character that can
  // never belong to a colour is wrong straight away.
  hexInvalid.value = !HEX_UNFINISHED.test(raw.trim())
}

function onHexBlur() {
  // Leaving the field is the moment an unfinished code IS wrong: without this, clicking Save
  // on a half-typed code would keep the old colour with nothing on screen to explain it. It is
  // also the only moment a '#RGB' shorthand can be read, since mid-typing it cannot be told
  // from the first half of a six-digit code.
  if (!hexText.value.trim()) {
    // An empty box is not "no colour" — the tag has one, and it is still on screen in the
    // picker and the preview chip. Put it back rather than inventing an error about it.
    hexText.value = '#' + props.color
    hexInvalid.value = false
    return
  }
  const settled = settledHex(hexText.value)
  hexInvalid.value = settled === null
  if (settled === null) return

  // Settle the code into the stored form FIRST — it is also the only feedback that shows a
  // shorthand was expanded ('#f0a' becoming '#ff00aa') — so the watch above recognises the
  // host's answer as our own echo rather than a colour change.
  hexText.value = '#' + settled
  // Only a code that actually differs is reported. A colour stored uppercase by an older
  // client must survive being focused and tabbed through untouched.
  if (settled !== props.color.toLowerCase()) emit('update:color', settled)
}
</script>

<template>
  <slot name="lead" />

  <Card class="tag-form-card" :class="{ flat }" :style="maxWidth ? { maxWidth } : undefined">
    <template #content>
      <div class="form-field">
        <label :for="`${idPrefix}-name`">{{ t('ui.tag_edit.name') }}</label>
        <InputText
          :id="`${idPrefix}-name`"
          :modelValue="props.name"
          :autofocus="autofocusName"
          :placeholder="namePlaceholder"
          class="w-full"
          @update:modelValue="emit('update:name', $event ?? '')"
        />
      </div>
      <div class="form-field">
        <label :id="`${idPrefix}-color-label`">{{ t('ui.tag_edit.color') }}</label>
        <div class="color-row">
          <ColorPicker
            :modelValue="props.color"
            :aria-labelledby="`${idPrefix}-color-label`"
            @update:modelValue="emit('update:color', String($event ?? ''))"
          />
          <!-- #303. The group label above names the PICKER (it is its `aria-labelledby`), so
               this control carries a name of its own — otherwise a screen reader announces two
               controls sharing one label. -->
          <InputText
            :id="`${idPrefix}-color-hex`"
            class="color-hex"
            :modelValue="hexText"
            :aria-label="t('ui.tag_edit.color_hex')"
            :aria-invalid="hexInvalid ? 'true' : undefined"
            :aria-describedby="hexInvalid ? `${idPrefix}-color-hex-error` : undefined"
            :invalid="hexInvalid"
            placeholder="#336699"
            maxlength="7"
            autocomplete="off"
            spellcheck="false"
            @update:modelValue="onHexInput($event ?? '')"
            @blur="onHexBlur"
          />
          <span class="color-preview" :style="{ background: '#' + props.color }"><TagIconMark :icon="props.icon" />{{
            props.name || t('ui.tag_edit.preview')
          }}</span>
        </div>
        <small v-if="hexInvalid" :id="`${idPrefix}-color-hex-error`" class="field-error" role="alert">
          {{ t('ui.tag_edit.color_hex_invalid') }}
        </small>
      </div>
      <TagIconField
        :icon="props.icon ?? null"
        :id-prefix="idPrefix"
        @update:icon="emit('update:icon', $event)"
      />
      <div class="form-field">
        <label :for="`${idPrefix}-parent`">{{ t('ui.tag_edit.parent') }}</label>
        <Select
          :modelValue="props.parent"
          :inputId="`${idPrefix}-parent`"
          :options="parentOptions"
          optionLabel="label"
          optionValue="value"
          class="w-full"
          showClear
          filter
          :placeholder="t('ui.tag_edit.no_parent')"
          @update:modelValue="emit('update:parent', $event ?? null)"
        />
      </div>
      <!-- Synonyms (#280). Rendered only for a host that manages them: the approved design puts
           them on the tag edit page, and a host that leaves the prop off renders the form exactly
           as it did before this section existed. -->
      <div v-if="props.synonyms !== undefined" class="form-field">
        <label :for="`${idPrefix}-synonym`">{{ t('ui.tag_edit.synonyms') }}</label>
        <p class="synonym-hint">{{ t('ui.tag_edit.synonyms_hint') }}</p>
        <div v-if="synonymList.length" class="synonym-chips">
          <span v-for="synonym in synonymList" :key="synonym" class="synonym-chip">
            {{ synonym }}
            <!-- The swap (TEEDY-153). An icon button rather than a labelled one: the chip's text
                 is the word itself, and every chip carries the pair of actions. -->
            <button
              type="button"
              class="synonym-promote"
              :aria-label="t('ui.tag_edit.synonym_make_main', { name: synonym })"
              :title="t('ui.tag_edit.synonym_make_main', { name: synonym })"
              @click="makeMainName(synonym)"
            >
              <i class="pi pi-arrow-up" aria-hidden="true" />
            </button>
            <!-- The split (TEEDY-154). Offered only for a word the server already holds: a chip
                 that has only been typed is not a synonym yet, so there is nothing to split. -->
            <button
              v-if="isStored(synonym)"
              type="button"
              class="synonym-split"
              :aria-label="t('ui.tag_edit.synonym_split', { name: synonym })"
              :title="t('ui.tag_edit.synonym_split', { name: synonym })"
              @click="requestSplit(synonym)"
            >
              <i class="pi pi-arrow-right" aria-hidden="true" />
            </button>
            <button
              type="button"
              class="synonym-remove"
              :aria-label="t('ui.tag_edit.synonym_remove', { name: synonym })"
              @click="removeSynonym(synonym)"
            >
              <i class="pi pi-times" aria-hidden="true" />
            </button>
          </span>
        </div>
        <!-- Two fields changed from one click, one of them out of sight above: the notice is
             what says so. Polite rather than an alert, like the notice under the input. -->
        <small v-if="pendingSwap" class="synonym-swap-notice" role="status" aria-live="polite">
          {{
            t('ui.tag_edit.synonym_swapped', {
              name: pendingSwap.promoted,
              previous: pendingSwap.demoted,
            })
          }}
        </small>
        <div class="synonym-row">
          <InputText
            :id="`${idPrefix}-synonym`"
            v-model="synonymDraft"
            class="synonym-input"
            :placeholder="t('ui.tag_edit.synonym_placeholder')"
            :aria-describedby="synonymNotice ? `${idPrefix}-synonym-notice` : undefined"
            autocomplete="off"
            @keydown.enter.prevent="addSynonym"
          />
          <Button
            type="button"
            severity="secondary"
            outlined
            :label="t('ui.tag_edit.synonym_add')"
            :disabled="!synonymDraft.trim() || draftOnThisTag"
            @click="addSynonym"
          />
        </div>
        <!-- A live status rather than an alert: it changes on every keystroke, and a screen
             reader announcing an alert that often would drown the field it belongs to. -->
        <small
          v-if="synonymNotice"
          :id="`${idPrefix}-synonym-notice`"
          class="synonym-notice"
          :class="synonymNotice.severity"
          role="status"
          aria-live="polite"
        >
          {{ synonymNotice.text }}
        </small>
      </div>
      <slot name="actions" />
    </template>
  </Card>

  <Card
    class="tag-form-card acl-card"
    :class="{ flat }"
    :style="maxWidth ? { maxWidth } : undefined"
  >
    <template #content>
      <h2 class="acl-heading">{{ t('ui.tag_acl.title') }}</h2>
      <p class="acl-desc">{{ t('ui.tag_acl.description') }}</p>
      <slot name="permissions-hint" />
      <AclEditor
        :source-id="acl.sourceId"
        :acls="acl.entries"
        :writable="acl.writable"
        :immutable="acl.immutable"
        :before-add="acl.beforeAdd"
        :deferred="acl.deferred"
        @changed="emit('acl-changed')"
        @add="emit('acl-add', $event)"
        @remove="emit('acl-remove', $event)"
      />
    </template>
  </Card>
</template>

<style scoped>
/*
 * Moved here from TagEdit.vue together with the markup they style. A scoped rule only reaches
 * the template it is written in, so leaving them behind would have silently unstyled the form
 * the moment it moved out of that file.
 */
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
  /* The hex field (#303) takes 120px of a row that also has to fit the preview chip inside a
     drawer as narrow as 360px, so the chip is allowed onto a second line rather than out of
     the panel. */
  flex-wrap: wrap;
}

/* #303. A fixed, monospaced box: '#RRGGBB' is always seven characters, so the field neither
   grows with the value nor lets the preview chip shift as one is typed. */
.color-hex {
  flex: 0 0 auto;
  width: 7.5rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.field-error {
  display: block;
  margin-top: 0.375rem;
  font-size: 0.75rem;
  color: var(--p-red-500);
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

/* Synonym chips (#280). Neutral rather than tag-coloured: a synonym is a NAME for the tag, not
   a tag of its own, and colouring it like one would suggest it appears on documents. */
.synonym-hint {
  margin: -0.125rem 0 0.5rem;
  font-size: 0.75rem;
  line-height: 1.4;
  color: var(--p-text-muted-color);
}

.synonym-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-bottom: 0.5rem;
}
.synonym-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.15rem 0.25rem 0.15rem 0.6rem;
  border: 1px solid var(--p-content-border-color);
  border-radius: 999px;
  background: var(--p-content-background);
  font-size: 0.8125rem;
}
.synonym-remove,
.synonym-promote,
.synonym-split {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.125rem;
  height: 1.125rem;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--p-text-muted-color);
  cursor: pointer;
}
.synonym-remove:hover,
.synonym-promote:hover,
.synonym-split:hover {
  background: var(--p-content-hover-background);
  color: var(--p-text-color);
}
.synonym-remove:focus-visible,
.synonym-promote:focus-visible,
.synonym-split:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--p-primary-color);
}
.synonym-remove .pi,
.synonym-promote .pi,
.synonym-split .pi {
  font-size: 0.625rem;
}

/* The swap message belongs to the chips above it, not to the input below. */
.synonym-swap-notice {
  display: block;
  margin: -0.125rem 0 0.5rem;
  font-size: 0.75rem;
  line-height: 1.4;
  color: var(--p-text-muted-color);
}

.synonym-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  /* The panel is as narrow as 360px, so the Add button drops under the field rather than
     squeezing it to nothing. */
  flex-wrap: wrap;
}
.synonym-input {
  flex: 1 1 12rem;
  min-width: 0;
}

.synonym-notice {
  display: block;
  margin-top: 0.375rem;
  font-size: 0.75rem;
  line-height: 1.4;
}
.synonym-notice.info {
  color: var(--p-text-muted-color);
}
.synonym-notice.warn {
  color: var(--p-orange-600, var(--p-text-color));
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

/*
 * The flat (side-panel) variant. The drawer the panel lives in is already a raised surface, so
 * a card inside it would be a second one; the same markup renders as plain sections instead.
 */
.tag-form-card.flat {
  background: none;
  box-shadow: none;
  border-radius: 0;
}
.tag-form-card.flat :deep(.p-card-body) {
  padding: 0;
  gap: 0;
}
.tag-form-card.flat.acl-card {
  margin-top: 1.5rem;
}
</style>
