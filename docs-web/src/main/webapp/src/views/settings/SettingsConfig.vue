<script setup lang="ts">
import { ref, watch } from 'vue'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import api from '../../api/client'
import Select from 'primevue/select'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const toast = useToast()
const queryClient = useQueryClient()

const defaultLanguage = ref('eng')
const tagSearchMode = ref('PREFIX')

const { data: appConfig } = useQuery({
  queryKey: ['app-config'],
  queryFn: () => api.get('/app').then((r) => r.data),
})

watch(appConfig, (config) => {
  if (config) {
    defaultLanguage.value = config.default_language || 'eng'
    tagSearchMode.value = config.tag_search_mode || 'PREFIX'
  }
}, { immediate: true })

const languages = [
  { label: 'English', value: 'eng' },
  { label: 'French', value: 'fra' },
  { label: 'German', value: 'deu' },
  { label: 'Spanish', value: 'spa' },
]

const searchModes = [
  { label: 'Prefix match (default)', value: 'PREFIX' },
  { label: 'Exact match', value: 'EXACT' },
]

const { mutate: saveConfig, isPending: saving } = useMutation({
  mutationFn: () => {
    const params = new URLSearchParams()
    params.set('default_language', defaultLanguage.value)
    params.set('tag_search_mode', tagSearchMode.value)
    return api.post('/app/config', params)
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['app-config'] })
    toast.add({ severity: 'success', summary: 'Configuration saved', life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: 'Failed to save configuration', life: 3000 })
  },
})
</script>

<template>
  <div>
    <h2>Configuration</h2>

    <section class="teedy-card p-4 mb-4" style="max-width: 520px">
      <h3>General</h3>
      <div class="form-field">
        <label>Default language for new documents</label>
        <Select v-model="defaultLanguage" :options="languages" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <div class="form-field">
        <label>Tag search mode</label>
        <Select v-model="tagSearchMode" :options="searchModes" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <Button label="Save" icon="pi pi-check" :loading="saving" @click="saveConfig()" />
    </section>
  </div>
</template>

<style scoped>
h3 { margin: 0 0 1rem; font-size: 1.125rem; }
.form-field {
  margin-bottom: 1rem;
}
.form-field label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #374151;
}
</style>
