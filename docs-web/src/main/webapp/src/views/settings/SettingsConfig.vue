<script setup lang="ts">
import { reactive, ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMutation, useQuery } from '@tanstack/vue-query'
import api from '../../api/client'
import { getSmtpConfig, saveSmtpConfig, smtpEnvManagedFields, cleanStorage, saveFooterLinks, type SmtpConfig, type SmtpEnvManagedField, type FooterLink } from '../../api/app'
import { SUPPORTED_LANGUAGES } from '../../constants/languages'
import { formatFileSize } from '../../utils/formatters'
import { useAppInfo, useInvalidateAppInfo } from '../../composables/useAppInfo'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import { useConfirmDanger } from '../../composables/useConfirmDanger'

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const invalidateAppInfo = useInvalidateAppInfo()

const defaultLanguage = ref('eng')
const tagSearchMode = ref('PREFIX')
const ocrEnabled = ref(true)
const maxUploadSize = ref(0)

const { data: appConfig } = useAppInfo()

// The user-editable text/select fields are SEEDED ONCE on first load. Reseeding on
// every query emission would clobber unsaved edits, because toggleOcr's onSuccess
// invalidates the app-info key and the ensuing refetch re-fires this watcher. Only the
// OCR toggle deliberately reconciles from the server on every emission — it has no
// Save button and relies on the refetch to confirm the persisted state (and the
// error path restores it). maxUploadSize is read-only (env-driven), so it can seed
// with the text fields on first load.
let seeded = false
watch(appConfig, (config) => {
  if (!config) return
  if (!seeded) {
    defaultLanguage.value = config.default_language || 'eng'
    tagSearchMode.value = config.tag_search_mode || 'PREFIX'
    maxUploadSize.value = config.max_upload_size || 524288000
    seeded = true
  }
  ocrEnabled.value = config.ocr_enabled !== false
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
    invalidateAppInfo()
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
    invalidateAppInfo()
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

// --- SMTP configuration ---
// The password is write-only: the GET never returns it (and there is NO password_set
// flag). We keep the field blank and show a "leave blank to keep" hint unconditionally.
const smtp = reactive<SmtpConfig>({ hostname: '', port: null, username: '', password: '', from: '' })

// Fields the backend OMITTED from the GET response are managed by a DOCS_SMTP_*
// environment variable (absent key), as opposed to present-but-null (unset,
// editable). Env-managed fields render disabled — a value typed there would be
// saved to the DB but silently shadowed by the env var at runtime.
const smtpEnvManaged = ref<Set<SmtpEnvManagedField>>(new Set())
const SMTP_ENV_VAR: Record<SmtpEnvManagedField, string> = {
  hostname: 'DOCS_SMTP_HOSTNAME',
  port: 'DOCS_SMTP_PORT',
  username: 'DOCS_SMTP_USERNAME',
}

const { data: smtpConfig } = useQuery({
  queryKey: ['smtp-config'],
  queryFn: () => getSmtpConfig(),
})

let smtpSeeded = false
watch(smtpConfig, (config) => {
  if (!config || smtpSeeded) return
  smtpEnvManaged.value = smtpEnvManagedFields(config)
  smtp.hostname = config.hostname ?? ''
  smtp.port = config.port ?? null
  smtp.username = config.username ?? ''
  smtp.from = config.from ?? ''
  smtp.password = ''
  smtpSeeded = true
}, { immediate: true })

const { mutate: saveSmtp, isPending: savingSmtp } = useMutation({
  mutationFn: () => {
    // Never POST an env-managed field: its input is disabled/blank, and the value
    // shown to the admin is the env var's, not whatever might linger in the DB.
    const payload: SmtpConfig = { ...smtp }
    for (const field of smtpEnvManaged.value) {
      delete payload[field]
    }
    return saveSmtpConfig(payload)
  },
  onSuccess: () => {
    // Clear the just-typed secret so it is not left in memory or re-sent on the next save.
    smtp.password = ''
    toast.add({ severity: 'success', summary: t('ui.smtp.saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.smtp.failed_save'), life: 3000 })
  },
})

// --- Footer / imprint links ---
// A configurable list (max 5) of {label, url} links rendered in the app shell footer
// AND on the login screen (EU imprint reachability). Seeded once from app-info so the
// invalidation-triggered refetch does not clobber unsaved edits.
const MAX_FOOTER_LINKS = 5
const footerLinks = ref<FooterLink[]>([])
let footerSeeded = false
watch(appConfig, (config) => {
  if (!config || footerSeeded) return
  footerLinks.value = (config.footer_links ?? []).map((l) => ({ label: l.label, url: l.url }))
  footerSeeded = true
}, { immediate: true })

function addFooterLink() {
  if (footerLinks.value.length >= MAX_FOOTER_LINKS) return
  footerLinks.value.push({ label: '', url: '' })
}
function removeFooterLink(index: number) {
  footerLinks.value.splice(index, 1)
}

const { mutate: saveFooter, isPending: savingFooter } = useMutation({
  // Persist only fully-filled rows so a half-typed row does not trip server validation;
  // an all-empty list clears the config.
  mutationFn: () =>
    saveFooterLinks(
      footerLinks.value
        .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
        .filter((l) => l.label !== '' || l.url !== ''),
    ),
  onSuccess: () => {
    invalidateAppInfo()
    toast.add({ severity: 'success', summary: t('ui.config.footer_links_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.config.footer_links_failed'), life: 3000 })
  },
})

const reindexing = ref(false)
const cleaningStorage = ref(false)

function handleCleanStorage() {
  confirmDanger({
    message: t('ui.config.clean_storage_confirm'),
    header: t('ui.config.clean_storage_header'),
    icon: 'pi pi-exclamation-triangle',
    accept: async () => {
      cleaningStorage.value = true
      try {
        await cleanStorage()
        toast.add({ severity: 'success', summary: t('ui.config.clean_storage_done'), life: 3000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.config.clean_storage_failed'), life: 3000 })
      } finally {
        cleaningStorage.value = false
      }
    },
  })
}

function handleReindex() {
  confirmDanger({
    message: t('ui.config.rebuild_confirm'),
    header: t('ui.config.rebuild_header'),
    icon: 'pi pi-exclamation-triangle',
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
      <h3>{{ t('ui.smtp.title') }}</h3>
      <p class="section-hint">{{ t('ui.smtp.hint') }}</p>
      <div class="form-field">
        <label for="smtp-hostname">{{ t('ui.smtp.hostname') }}</label>
        <InputText id="smtp-hostname" v-model="smtp.hostname" class="w-full" :disabled="smtpEnvManaged.has('hostname')" />
        <small v-if="smtpEnvManaged.has('hostname')" class="field-hint">{{ t('ui.smtp.env_managed', { env: SMTP_ENV_VAR.hostname }) }}</small>
      </div>
      <div class="form-field">
        <label for="smtp-port">{{ t('ui.smtp.port') }}</label>
        <InputNumber inputId="smtp-port" v-model="smtp.port" :useGrouping="false" class="w-full" :min="0" :disabled="smtpEnvManaged.has('port')" />
        <small v-if="smtpEnvManaged.has('port')" class="field-hint">{{ t('ui.smtp.env_managed', { env: SMTP_ENV_VAR.port }) }}</small>
      </div>
      <div class="form-field">
        <label for="smtp-from">{{ t('ui.smtp.from') }}</label>
        <InputText id="smtp-from" v-model="smtp.from" class="w-full" />
      </div>
      <div class="form-field">
        <label for="smtp-username">{{ t('ui.smtp.username') }}</label>
        <InputText id="smtp-username" v-model="smtp.username" :autocomplete="'off'" class="w-full" :disabled="smtpEnvManaged.has('username')" />
        <small v-if="smtpEnvManaged.has('username')" class="field-hint">{{ t('ui.smtp.env_managed', { env: SMTP_ENV_VAR.username }) }}</small>
      </div>
      <div class="form-field">
        <label for="smtp-password">{{ t('ui.smtp.password') }}</label>
        <Password inputId="smtp-password" v-model="smtp.password" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'smtp-password' }" :placeholder="t('ui.smtp.password_keep')" inputClass="w-full" class="w-full" />
        <small class="field-hint">{{ t('ui.smtp.password_keep') }}</small>
      </div>
      <Button :label="t('save')" icon="pi pi-check" :loading="savingSmtp" @click="saveSmtp()" />
    </template></Card>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.config.footer_links_title') }}</h3>
      <p class="section-hint">{{ t('ui.config.footer_links_hint') }}</p>
      <div v-for="(link, index) in footerLinks" :key="index" class="footer-link-row">
        <InputText
          v-model="link.label"
          :placeholder="t('ui.config.footer_links_label')"
          :maxlength="40"
          class="footer-link-label"
        />
        <InputText
          v-model="link.url"
          :placeholder="t('ui.config.footer_links_url')"
          :maxlength="500"
          class="footer-link-url"
        />
        <Button
          icon="pi pi-trash"
          severity="danger"
          text
          :aria-label="t('ui.config.footer_links_remove')"
          @click="removeFooterLink(index)"
        />
      </div>
      <p v-if="footerLinks.length === 0" class="section-hint footer-links-empty">
        {{ t('ui.config.footer_links_empty') }}
      </p>
      <div class="footer-links-actions">
        <Button
          :label="t('ui.config.footer_links_add')"
          icon="pi pi-plus"
          severity="secondary"
          outlined
          :disabled="footerLinks.length >= MAX_FOOTER_LINKS"
          @click="addFooterLink"
        />
        <Button :label="t('save')" icon="pi pi-check" :loading="savingFooter" @click="saveFooter()" />
      </div>
    </template></Card>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.config.maintenance') }}</h3>
      <p class="section-hint">
        {{ t('ui.config.maintenance_hint') }}
      </p>
      <div class="maintenance-actions">
        <Button
          :label="t('ui.config.rebuild_index')"
          icon="pi pi-sync"
          severity="danger"
          outlined
          :loading="reindexing"
          @click="handleReindex"
        />
        <Button
          :label="t('ui.config.clean_storage')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          :loading="cleaningStorage"
          @click="handleCleanStorage"
        />
      </div>
      <p class="section-hint clean-storage-hint">{{ t('ui.config.clean_storage_hint') }}</p>
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
.maintenance-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}
.clean-storage-hint {
  margin: 0.75rem 0 0;
}
.footer-link-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.footer-link-label {
  flex: 0 0 35%;
  min-width: 0;
}
.footer-link-url {
  flex: 1 1 auto;
  min-width: 0;
}
.footer-links-empty {
  margin: 0 0 0.75rem;
}
.footer-links-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 0.5rem;
}
.w-full {
  width: 100%;
}
</style>
