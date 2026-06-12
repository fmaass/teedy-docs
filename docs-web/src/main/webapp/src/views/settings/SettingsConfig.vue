<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import api from '../../api/client'
import { SUPPORTED_LANGUAGES } from '../../constants/languages'
import { formatFileSize } from '../../composables/useFormatters'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const queryClient = useQueryClient()

const defaultLanguage = ref('eng')
const tagSearchMode = ref('PREFIX')
const ocrEnabled = ref(true)
const maxUploadSize = ref(0)

const { data: appConfig } = useQuery({
  queryKey: ['app-config'],
  queryFn: () => api.get('/app').then((r) => r.data),
})

watch(appConfig, (config) => {
  if (config) {
    defaultLanguage.value = config.default_language || 'eng'
    tagSearchMode.value = config.tag_search_mode || 'PREFIX'
    ocrEnabled.value = config.ocr_enabled !== false
    maxUploadSize.value = config.max_upload_size || 524288000
  }
}, { immediate: true })

const searchModes = computed(() => [
  { label: t('ui.config.prefix_match'), value: 'PREFIX' },
  { label: t('ui.config.exact_match'), value: 'EXACT' },
])

const { mutate: saveConfig, isPending: saving } = useMutation({
  mutationFn: () => {
    const params = new URLSearchParams()
    params.set('default_language', defaultLanguage.value)
    params.set('tag_search_mode', tagSearchMode.value)
    return api.post('/app/config', params)
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['app-config'] })
    toast.add({ severity: 'success', summary: t('ui.config.config_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.config.failed_save'), life: 3000 })
  },
})

const { mutate: toggleOcr, isPending: togglingOcr } = useMutation({
  mutationFn: (enabled: boolean) => {
    const params = new URLSearchParams()
    params.set('enabled', String(enabled))
    return api.post('/app/ocr', params)
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['app-config'] })
    toast.add({ severity: 'success', summary: ocrEnabled.value ? t('ui.config.ocr_enabled') : t('ui.config.ocr_disabled'), life: 2000 })
  },
  onError: () => {
    ocrEnabled.value = !ocrEnabled.value
    toast.add({ severity: 'error', summary: t('ui.config.ocr_toggle_failed'), life: 3000 })
  },
})

function handleOcrToggle(val: boolean) {
  ocrEnabled.value = val
  toggleOcr(val)
}

const reindexing = ref(false)

function handleReindex() {
  confirm.require({
    message: t('ui.config.rebuild_confirm'),
    header: t('ui.config.rebuild_header'),
    icon: 'pi pi-exclamation-triangle',
    acceptProps: { severity: 'danger' },
    rejectProps: { severity: 'secondary', outlined: true },
    accept: async () => {
      reindexing.value = true
      try {
        await api.post('/app/batch/reindex')
        toast.add({ severity: 'success', summary: t('ui.config.rebuild_started'), life: 3000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.config.rebuild_failed'), life: 3000 })
      } finally {
        reindexing.value = false
      }
    },
  })
}
</script>

<template>
  <div>
    <h2>{{ t('ui.config.title') }}</h2>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.config.general') }}</h3>
      <div class="form-field">
        <label for="cfg-language">{{ t('ui.config.default_language') }}</label>
        <Select v-model="defaultLanguage" inputId="cfg-language" :options="SUPPORTED_LANGUAGES" optionLabel="label" optionValue="value" class="w-full" filter />
      </div>
      <div class="form-field">
        <label for="cfg-tag-search">{{ t('ui.config.tag_search_mode') }}</label>
        <Select v-model="tagSearchMode" inputId="cfg-tag-search" :options="searchModes" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <div class="form-field">
        <label>{{ t('ui.config.max_upload_size') }}</label>
        <div class="read-only-value">{{ formatFileSize(maxUploadSize) }}</div>
        <small class="field-hint">{{ t('ui.config.max_upload_env_hint', { env: 'DOCS_MAX_UPLOAD_SIZE' }) }}</small>
      </div>
      <Button :label="t('save')" icon="pi pi-check" :loading="saving" @click="saveConfig()" />
    </template></Card>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.config.ocr_title') }}</h3>
      <p class="section-hint">
        {{ t('ui.config.ocr_hint') }}
      </p>
      <div class="ocr-toggle">
        <ToggleSwitch
          :modelValue="ocrEnabled"
          @update:modelValue="handleOcrToggle"
          :disabled="togglingOcr"
        />
        <span>{{ ocrEnabled ? t('ui.config.ocr_enabled') : t('ui.config.ocr_disabled') }}</span>
      </div>
    </template></Card>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.config.maintenance') }}</h3>
      <p class="section-hint">
        {{ t('ui.config.maintenance_hint') }}
      </p>
      <Button
        :label="t('ui.config.rebuild_index')"
        icon="pi pi-sync"
        severity="danger"
        outlined
        :loading="reindexing"
        @click="handleReindex"
      />
    </template></Card>
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
  color: var(--p-text-color);
}
.section-hint {
  margin: 0 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  line-height: 1.5;
}
.read-only-value {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-color);
}
.field-hint {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}
.field-hint code {
  font-size: 0.7rem;
  background: var(--p-content-hover-background);
  padding: 0.1rem 0.3rem;
  border-radius: 3px;
}
.ocr-toggle {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
}
</style>
