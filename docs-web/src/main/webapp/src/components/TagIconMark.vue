<script setup lang="ts">
/**
 * A tag's icon (#287), drawn wherever a tag is drawn: the chip (TagBadge), the sidebar tag tree
 * and the tag management tree.
 *
 * THE ONE THING THIS COMPONENT EXISTS TO GUARANTEE: a tag with NO icon adds NOTHING to the DOM.
 * Not an empty element, not a wrapper, not a placeholder comment. Three of the surfaces above are
 * screenshotted by the standing visual gate (the document list — left panel and all — the gallery
 * and the slide-over), 28 baselines deep, and none of their fixtures carries an icon. An icon
 * feature that left a node behind on every untagged chip would move every one of those PNGs.
 *
 * That is why the markup is a `v-for` over a 0-or-1 array rather than the obvious `v-if`. A false
 * `v-if` compiles to a `<!--v-if-->` comment anchor, which IS a node; a `v-for` over an empty
 * array leaves only the fragment's two anchors, which are empty TEXT nodes and serialize to
 * nothing. TagBadge.icon.spec.ts freezes the resulting HTML as a literal captured from the chip as
 * it stood before icons existed, and fails if so much as a comment reappears.
 */
import { computed, ref, watch } from 'vue'
import { parseTagIcon, tagIconDataUrl } from '../utils/tagIcon'
import { tagIconsVisible } from '../composables/useTagIcons'

const props = defineProps<{
  /** The tag's stored icon: `emoji:<grapheme>`, `set:<iconId>`, or nothing. */
  icon?: string | null
}>()

/**
 * Set when an uploaded icon's image fails to load, which drops it rather than leaving a broken
 * image in the middle of a tag. The authoritative fallback is server-side — deleting an icon
 * clears the reference off every tag that used it — so this only ever fires for a client still
 * holding a tag list from before that happened.
 */
const iconError = ref(false)
watch(
  () => props.icon,
  () => {
    iconError.value = false
  },
)

const iconList = computed(() => {
  if (!tagIconsVisible.value || iconError.value) return []
  const parsed = parseTagIcon(props.icon)
  if (!parsed) return []
  return parsed.kind === 'emoji'
    ? [{ key: props.icon as string, emoji: parsed.emoji, src: null as string | null }]
    : [{ key: props.icon as string, emoji: null as string | null, src: tagIconDataUrl(parsed.id) }]
})
</script>

<template><template v-for="entry in iconList" :key="entry.key"><img v-if="entry.src" class="tag-icon" :src="entry.src" alt="" aria-hidden="true" @error="iconError = true" /><span v-else class="tag-icon tag-icon-emoji" aria-hidden="true">{{ entry.emoji }}</span></template></template>

<style scoped>
/*
 * 16px square: the bottom of the range the reporter asked for ("the size should be sth. between
 * 16x16 and 24x24px"), chosen there because a tag chip's own text is 12px — a 24px icon would make
 * every tagged row half again as tall. Fixed rather than relative so an uploaded PNG of any pixel
 * size lands at exactly one size, with `object-fit: contain` keeping a non-square upload from
 * being stretched.
 *
 * These rules can only ever apply to a tag that HAS an icon: with no icon there is no element.
 */
.tag-icon {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  margin-right: 0.25rem;
  vertical-align: middle;
}
img.tag-icon {
  object-fit: contain;
}
/* An emoji is drawn by the font, so it is CENTRED in its box rather than scaled to it: the
   font-size is what sets its size, and line-height 1 stops it adding leading to a chip whose own
   line-height is 1.5. */
.tag-icon-emoji {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  line-height: 1;
}
</style>
