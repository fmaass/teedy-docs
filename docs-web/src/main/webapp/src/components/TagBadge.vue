<script setup lang="ts">
defineProps<{
  name: string
  color: string
}>()
</script>

<template>
  <span class="teedy-tag" :style="{ backgroundColor: color }">{{ name }}</span>
</template>
