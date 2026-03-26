<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../../api/client'
import Select from 'primevue/select'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const toast = useToast()

const defaultLanguage = ref('eng')
const tagSearchMode = ref('PREFIX')
const saving = ref(false)

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

onMounted(async () => {
  try {
    const { data } = await api.get('/app')
    defaultLanguage.value = data.default_language || 'eng'
    tagSearchMode.value = data.tag_search_mode || 'PREFIX'
  } catch { /* ignore */ }
})

async function handleSave() {
  saving.value = true
  try {
    const params = new URLSearchParams()
    params.set('default_language', defaultLanguage.value)
    params.set('tag_search_mode', tagSearchMode.value)
    await api.post('/app/config', params)
    toast.add({ severity: 'success', summary: 'Configuration saved', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to save configuration', life: 3000 })
  } finally {
    saving.value = false
  }
}
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
      <Button label="Save" icon="pi pi-check" :loading="saving" @click="handleSave" />
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
