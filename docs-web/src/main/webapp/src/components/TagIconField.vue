<script setup lang="ts">
/**
 * Choosing a tag's icon (#287) — the field the shared TagForm hosts, so both the tag management
 * page and the document editor's create panel get it.
 *
 * Two sources, one at a time, because a tag has one icon:
 *
 *  - an EMOJI, typed or pasted. This is what the reporter is already doing by hand — "we can
 *    already mess around with copy paste emojis" — so the field takes a pasted one directly and
 *    the grid beside it is a shortcut, not the only way in. There is deliberately no emoji-picker
 *    library: a curated list of three dozen covers the cases he named (companies, topics,
 *    warnings) and every operating system has a real picker one keystroke away.
 *  - an icon from the instance's uploaded SET, which is where a company or vendor logo lives.
 *    The set is maintained on the tag management page by an administrator.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery } from '@tanstack/vue-query'
import InputText from 'primevue/inputtext'
import SelectButton from 'primevue/selectbutton'
import { listTagIcons } from '../api/tag'
import { queryKeys } from '../api/queryKeys'
import {
  MAX_EMOJI_LENGTH,
  SUGGESTED_EMOJI,
  emojiIconRef,
  isSingleEmoji,
  parseTagIcon,
  setIconRef,
  tagIconDataUrl,
} from '../utils/tagIcon'

const props = defineProps<{
  /** The stored icon reference, or null for none. */
  icon: string | null
  /** Prefixes every field id, matching the rest of TagForm. */
  idPrefix: string
}>()

const emit = defineEmits<{
  'update:icon': [value: string | null]
}>()

const { t } = useI18n()

type IconSource = 'none' | 'emoji' | 'set'

/**
 * Which source the user is working in. Held on its own rather than derived from the stored value,
 * because "Emoji, nothing typed yet" and "Icon set, nothing picked yet" are real states the stored
 * value cannot express: both report NO icon, so a control derived from the value would snap back
 * to "None" the instant the user opened either of them.
 */
const source = ref<IconSource>('none')
const emojiText = ref('')

const parsed = computed(() => parseTagIcon(props.icon))

/**
 * The last value this field REPORTED, or `undefined` when it has reported nothing yet.
 *
 * The watch below re-derives the whole control from the stored value. That is right when the host
 * loads a different tag, and wrong when the value changed because the user just clicked something
 * here: opening "Icon set" — or "Emoji" with an empty box — legitimately reports NO icon, and
 * re-deriving from that would snap the control back to "None" under the user's cursor.
 *
 * Comparing the incoming value against what was last reported tells the two apart WITHOUT
 * depending on when the host answers. A flag set around the emit would work only while the host
 * answers synchronously, which is true of a `v-model` ref and of nothing else.
 */
let lastReported: string | null | undefined

function report(value: string | null) {
  lastReported = value
  emit('update:icon', value)
}

watch(
  () => props.icon,
  (value) => {
    const incoming = value ?? null
    // Our own echo coming back: the control already shows this, and re-deriving would undo a
    // source the user has just opened.
    if (lastReported !== undefined && incoming === lastReported) return
    lastReported = undefined
    const stored = parseTagIcon(incoming)
    source.value = stored ? stored.kind : 'none'
    emojiText.value = stored?.kind === 'emoji' ? stored.emoji : ''
  },
  { immediate: true },
)

const sourceOptions = computed(() => [
  { label: t('ui.tag_icon.none'), value: 'none' as const },
  { label: t('ui.tag_icon.emoji'), value: 'emoji' as const },
  { label: t('ui.tag_icon.from_set'), value: 'set' as const },
])

const { data: icons, isLoading: iconsLoading } = useQuery({
  queryKey: queryKeys.tagIcons(),
  queryFn: () => listTagIcons().then((r) => r.data.icons),
  staleTime: 60_000,
})

const selectedSetId = computed(() => (parsed.value?.kind === 'set' ? parsed.value.id : null))

/**
 * True when something has been typed that is not one emoji. An EMPTY box is not wrong — it is the
 * state the field starts in — so it reports nothing until there is something to complain about.
 */
const emojiInvalid = computed(() => !!emojiText.value.trim() && !isSingleEmoji(emojiText.value))

function onSourceChange(value: IconSource | null) {
  // `allowEmpty` is off, so a null only arrives if PrimeVue ever changes that; treat it as "none".
  source.value = value ?? 'none'
  if (source.value === 'none') {
    emojiText.value = ''
    report(null)
    return
  }
  if (source.value === 'emoji') {
    // Report whatever is already in the box, so switching back to a typed emoji restores it.
    report(isSingleEmoji(emojiText.value) ? emojiIconRef(emojiText.value) : null)
    return
  }
  // Switching to the set does not pick anything on the user's behalf: an icon is a choice.
  report(null)
}

function onEmojiInput(value: string) {
  emojiText.value = value
  // Only a value that IS one emoji is reported. Half a paste is not an icon, and reporting it
  // would let a Save land on something the server would refuse anyway.
  report(isSingleEmoji(value) ? emojiIconRef(value) : null)
}

function chooseEmoji(emoji: string) {
  emojiText.value = emoji
  report(emojiIconRef(emoji))
}

function chooseSetIcon(id: string) {
  // Clicking the selected icon again clears it — the same affordance the colour swatches have.
  report(selectedSetId.value === id ? null : setIconRef(id))
}
</script>

<template>
  <div class="form-field tag-icon-field">
    <label :id="`${idPrefix}-icon-label`">{{ t('ui.tag_icon.label') }}</label>
    <SelectButton
      :modelValue="source"
      :options="sourceOptions"
      optionLabel="label"
      optionValue="value"
      :allowEmpty="false"
      size="small"
      :aria-labelledby="`${idPrefix}-icon-label`"
      class="icon-source-toggle"
      @update:modelValue="onSourceChange($event as IconSource | null)"
    />

    <div v-if="source === 'emoji'" class="icon-emoji-panel">
      <InputText
        :id="`${idPrefix}-icon-emoji`"
        class="icon-emoji-input"
        :modelValue="emojiText"
        :aria-label="t('ui.tag_icon.emoji_input')"
        :aria-invalid="emojiInvalid ? 'true' : undefined"
        :aria-describedby="emojiInvalid ? `${idPrefix}-icon-emoji-error` : undefined"
        :invalid="emojiInvalid"
        :maxlength="MAX_EMOJI_LENGTH"
        autocomplete="off"
        @update:modelValue="onEmojiInput($event ?? '')"
      />
      <small v-if="emojiInvalid" :id="`${idPrefix}-icon-emoji-error`" class="field-error" role="alert">
        {{ t('ui.tag_icon.emoji_invalid') }}
      </small>
      <div class="icon-emoji-grid" role="group" :aria-label="t('ui.tag_icon.suggested')">
        <button
          v-for="emoji in SUGGESTED_EMOJI"
          :key="emoji"
          type="button"
          class="icon-emoji-option"
          :class="{ selected: emojiText === emoji }"
          :aria-pressed="emojiText === emoji"
          :title="emoji"
          @click="chooseEmoji(emoji)"
        >{{ emoji }}</button>
      </div>
    </div>

    <div v-else-if="source === 'set'" class="icon-set-panel">
      <div v-if="iconsLoading" class="icon-set-hint">{{ t('ui.tag_icon.loading') }}</div>
      <div v-else-if="!icons?.length" class="icon-set-hint">{{ t('ui.tag_icon.set_empty') }}</div>
      <div v-else class="icon-set-grid" role="group" :aria-label="t('ui.tag_icon.from_set')">
        <button
          v-for="entry in icons"
          :key="entry.id"
          type="button"
          class="icon-set-option"
          :class="{ selected: selectedSetId === entry.id }"
          :aria-pressed="selectedSetId === entry.id"
          :title="entry.name"
          @click="chooseSetIcon(entry.id)"
        >
          <img :src="tagIconDataUrl(entry.id)" :alt="entry.name" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tag-icon-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--p-text-color);
}

.icon-source-toggle :deep(.p-selectbutton) {
  flex-wrap: wrap;
}

.icon-emoji-panel,
.icon-set-panel {
  margin-top: 0.625rem;
}

/* Wide enough for a ZWJ family plus the caret, and no wider — the value is one glyph. */
.icon-emoji-input {
  width: 5rem;
  font-size: 1.125rem;
  text-align: center;
}

.field-error {
  display: block;
  margin-top: 0.375rem;
  font-size: 0.75rem;
  color: var(--p-red-500);
}

.icon-emoji-grid,
.icon-set-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-top: 0.5rem;
  /* The grid is a shortcut, not the field: in a 360px-wide drawer it scrolls rather than
     pushing the Save button off the bottom. */
  max-height: 8.5rem;
  overflow-y: auto;
}

.icon-emoji-option,
.icon-set-option {
  all: unset;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 1.125rem;
  line-height: 1;
}
.icon-emoji-option:hover,
.icon-set-option:hover {
  background: var(--p-content-hover-background);
}
.icon-emoji-option:focus-visible,
.icon-set-option:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--p-primary-color);
}
.icon-emoji-option.selected,
.icon-set-option.selected {
  border-color: var(--p-primary-color);
  background: var(--p-highlight-background);
}

.icon-set-option img {
  width: 20px;
  height: 20px;
  object-fit: contain;
}

.icon-set-hint {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
</style>
