<script setup lang="ts">
import { inject, type Ref } from 'vue'
import { type DocumentDetail } from '../../api/document'

const doc = inject<Ref<DocumentDetail | null>>('document')!
</script>

<template>
  <div v-if="doc">
    <p class="text-sm text-muted mb-4">
      Permissions control who can read or write to this document.
      Permissions can also be inherited from tags.
    </p>
    <div class="teedy-empty">
      <i class="pi pi-lock" />
      <p>Permissions management coming soon</p>
    </div>
  </div>
</template>
