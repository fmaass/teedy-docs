<script setup lang="ts">
import { ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import Popover from 'primevue/popover'
import TagBadge from './TagBadge.vue'
import type { Tag } from '../api/tag'

const { t } = useI18n()

const props = defineProps<{
  /** The tags that overflow the inline row — shown inside the reveal panel. */
  tags: Pick<Tag, 'id' | 'name' | 'color'>[]
}>()

// Stable id so the trigger can reference the panel for assistive tech.
const panelId = useId()
const panel = ref<InstanceType<typeof Popover> | null>(null)
const open = ref(false)

// Toggle the Popover, which PrimeVue teleports to <body> — this escapes the
// DataTable's `overflow: auto` clipping context that would otherwise cut off a
// CSS-positioned panel. Click/keyboard activation is stopped by the template so
// the DataTable row underneath is never triggered. `open` is flipped eagerly here
// (not solely from @show, which PrimeVue fires after a transition jsdom skips) so
// aria-expanded is accurate immediately; @show/@hide reconcile it for pointer
// dismissals (Escape / outside-click) the trigger never sees.
function toggle(event: Event) {
  open.value = !open.value
  panel.value?.toggle(event)
}

function onShow() {
  open.value = true
}

function onHide() {
  open.value = false
}
</script>

<template>
  <span
    class="tag-overflow"
    tabindex="0"
    role="button"
    :aria-label="t('ui.more_tags', { count: props.tags.length })"
    :aria-controls="panelId"
    :aria-expanded="open"
    @click.stop="toggle"
    @keydown.enter.stop.prevent="toggle"
    @keydown.space.stop.prevent="toggle"
  >
    +{{ props.tags.length }}
    <Popover ref="panel" :id="panelId" @show="onShow" @hide="onHide" @click.stop>
      <div class="tag-overflow-panel">
        <TagBadge
          v-for="tag in props.tags"
          :key="tag.id"
          :name="tag.name"
          :color="tag.color"
        />
      </div>
    </Popover>
  </span>
</template>

<style scoped>
.tag-overflow {
  font-size: 0.6875rem;
  color: var(--p-text-muted-color);
  align-self: center;
  cursor: pointer;
  border-radius: 4px;
  padding: 0 0.15rem;
  outline: none;
}
.tag-overflow:focus-visible {
  box-shadow: 0 0 0 2px var(--p-primary-color);
}

.tag-overflow-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 0.2rem;
  max-width: 240px;
}
</style>
