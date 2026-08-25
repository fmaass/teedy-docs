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
 * - `idPrefix` prefixes every field id. `tag-name`/`tag-color-label`/`tag-parent` are e2e
 *   selectors on the management page (e2e/tags.spec.ts), so that host keeps `tag`; the panel
 *   takes its own prefix, which is also what stops the two forms colliding on ids should they
 *   ever share a page.
 * - `parentOptions` is the host's to build: the management page must exclude the tag itself
 *   and its descendants (a cycle), while a tag that does not exist yet has neither.
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Card from 'primevue/card'
import AclEditor from './AclEditor.vue'
import type { AclEntry, AclTarget } from '../api/acl'

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
}>()

const emit = defineEmits<{
  'update:name': [value: string]
  'update:color': [value: string]
  'update:parent': [value: string | null]
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
          <span class="color-preview" :style="{ background: '#' + props.color }">{{
            props.name || t('ui.tag_edit.preview')
          }}</span>
        </div>
        <small v-if="hexInvalid" :id="`${idPrefix}-color-hex-error`" class="field-error" role="alert">
          {{ t('ui.tag_edit.color_hex_invalid') }}
        </small>
      </div>
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
