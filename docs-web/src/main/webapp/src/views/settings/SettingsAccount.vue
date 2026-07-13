<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import { setLocale } from '../../i18n'
import { useThemeSwitch, themeNames, getStoredTheme } from '../../composables/useThemeSwitch'
import Password from 'primevue/password'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Card from 'primevue/card'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import api from '../../api/client'
import { listSessions, deleteOtherSessions, type UserSession } from '../../api/user'
import { useConfirmDanger } from '../../composables/useConfirmDanger'

const { t } = useI18n()
const auth = useAuthStore()
const toast = useToast()
const { switchTheme } = useThemeSwitch()
const { confirmDanger } = useConfirmDanger()

const currentPassword = ref('')
const password = ref('')
const passwordConfirm = ref('')
const saving = ref(false)

const languages = [
  { label: 'English', value: 'en' },
  { label: 'Deutsch', value: 'de' },
  { label: 'Español', value: 'es' },
  { label: 'Français', value: 'fr' },
  { label: 'Italiano', value: 'it' },
  { label: 'Português', value: 'pt' },
  { label: 'Polski', value: 'pl' },
  { label: 'Ελληνικά', value: 'el' },
  { label: 'Русский', value: 'ru' },
  { label: '中文（简体）', value: 'zh_CN' },
  { label: '中文（繁體）', value: 'zh_TW' },
  { label: 'Shqip', value: 'sq_AL' },
]

const themeOptions = themeNames.map((n) => ({ label: n, value: n }))

const selectedLocale = ref(localStorage.getItem('teedy-locale') || 'en')
const selectedTheme = ref(getStoredTheme())

interface SelectChangeEvent {
  value: string
}

// --- Active sessions (self-service; not admin-gated) ---
const sessions = ref<UserSession[]>([])
const sessionsLoading = ref(false)
const revokingSessions = ref(false)

// True once more than one session exists — the "sign out other sessions" action is
// only meaningful when there is another session to revoke.
const hasOtherSessions = computed(() => sessions.value.length > 1)

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const { data } = await listSessions()
    sessions.value = data.sessions ?? []
  } catch {
    toast.add({ severity: 'error', summary: t('ui.account.sessions.load_failed'), life: 3000 })
  } finally {
    sessionsLoading.value = false
  }
}

function confirmRevokeOthers() {
  confirmDanger({
    header: t('ui.account.sessions.revoke_title'),
    message: t('ui.account.sessions.revoke_confirm'),
    icon: 'pi pi-sign-out',
    accept: async () => {
      revokingSessions.value = true
      try {
        await deleteOtherSessions()
        await loadSessions()
        toast.add({ severity: 'success', summary: t('ui.account.sessions.revoked'), life: 2000 })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.account.sessions.revoke_failed'), life: 3000 })
      } finally {
        revokingSessions.value = false
      }
    },
  })
}

function formatSessionDate(ts?: number): string {
  if (ts == null) return '—'
  return new Date(ts).toLocaleString()
}

onMounted(() => {
  selectedLocale.value = localStorage.getItem('teedy-locale') || 'en'
  loadSessions()
})

async function handleSave() {
  if (!currentPassword.value) {
    toast.add({ severity: 'warn', summary: t('ui.account.current_password_required'), life: 2000 })
    return
  }
  if (password.value.length < 8) {
    toast.add({ severity: 'warn', summary: t('ui.account.password_min_length'), life: 2000 })
    return
  }
  if (password.value !== passwordConfirm.value) {
    toast.add({ severity: 'warn', summary: t('ui.account.passwords_mismatch'), life: 2000 })
    return
  }

  saving.value = true
  try {
    const params = new URLSearchParams()
    params.set('current_password', currentPassword.value)
    params.set('password', password.value)
    await api.post('/user', params)
    currentPassword.value = ''
    password.value = ''
    passwordConfirm.value = ''
    toast.add({ severity: 'success', summary: t('ui.account.password_updated'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.account.failed_update_password'), life: 3000 })
  } finally {
    saving.value = false
  }
}

async function handleLocaleChange(locale: string) {
  await setLocale(locale)
  localStorage.setItem('teedy-locale', locale)
  toast.add({ severity: 'success', summary: t('ui.account.language_updated'), life: 2000 })
}

async function handleThemeChange(name: string) {
  await switchTheme(name)
  toast.add({ severity: 'success', summary: t('ui.account.theme_switched', { name }), life: 2000 })
}

function onThemeSelect(event: SelectChangeEvent) {
  handleThemeChange(event.value)
}

function onLocaleSelect(event: SelectChangeEvent) {
  handleLocaleChange(event.value)
}
</script>

<template>
  <div>
    <h2>{{ t('ui.account.title') }}</h2>
    <p class="text-sm text-muted mb-4">
      {{ t('ui.account.logged_in_as', { username: auth.username }) }}
      <span v-if="auth.user?.email"> ({{ auth.user.email }})</span>
    </p>

    <!-- Appearance -->
    <Card class="mb-3" style="max-width: 400px"><template #content>
      <h3 class="section-title">{{ t('ui.account.appearance') }}</h3>
      <div class="form-field">
        <label for="account-theme">{{ t('ui.account.theme') }}</label>
        <Select
          v-model="selectedTheme"
          inputId="account-theme"
          :options="themeOptions"
          optionLabel="label"
          optionValue="value"
          class="w-full"
          @change="onThemeSelect"
        />
      </div>
      <div class="form-field">
        <label for="account-locale">{{ t('ui.account.language') }}</label>
        <Select
          v-model="selectedLocale"
          inputId="account-locale"
          :options="languages"
          optionLabel="label"
          optionValue="value"
          class="w-full"
          @change="onLocaleSelect"
        />
      </div>
    </template></Card>

    <!-- Password -->
    <Card style="max-width: 400px"><template #content>
      <h3 class="section-title">{{ t('ui.account.change_password') }}</h3>
      <form @submit.prevent="handleSave">
        <div class="form-field">
          <label for="account-current-pass">{{ t('ui.account.current_password') }}</label>
          <Password v-model="currentPassword" inputId="account-current-pass" :feedback="false" toggleMask :inputProps="{ autocomplete: 'current-password', name: 'current-password' }" inputClass="w-full" class="w-full" />
        </div>
        <div class="form-field">
          <label for="account-new-pass">{{ t('ui.account.new_password') }}</label>
          <Password v-model="password" inputId="account-new-pass" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'new-password' }" inputClass="w-full" class="w-full" />
        </div>
        <div class="form-field">
          <label for="account-confirm-pass">{{ t('ui.account.confirm_password') }}</label>
          <Password v-model="passwordConfirm" inputId="account-confirm-pass" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'confirm-password' }" inputClass="w-full" class="w-full" />
        </div>
        <Button type="submit" :label="t('save')" icon="pi pi-check" :loading="saving" />
      </form>
    </template></Card>

    <!-- Active sessions -->
    <Card class="mt-3 sessions-card"><template #content>
      <div class="sessions-header">
        <h3 class="section-title">{{ t('ui.account.sessions.title') }}</h3>
        <Button
          :label="t('ui.account.sessions.revoke_others')"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          :disabled="!hasOtherSessions"
          :loading="revokingSessions"
          @click="confirmRevokeOthers"
        />
      </div>
      <p class="text-sm text-muted mb-3">{{ t('ui.account.sessions.description') }}</p>
      <DataTable :value="sessions" :loading="sessionsLoading" stripedRows class="sessions-table">
        <Column :header="t('ui.account.sessions.created')" style="width: 190px">
          <template #body="{ data }">
            <span class="session-cell">{{ formatSessionDate(data.create_date) }}</span>
            <Tag v-if="data.current" :value="t('ui.account.sessions.current')" severity="success" class="ml-2" />
          </template>
        </Column>
        <Column :header="t('ui.account.sessions.last_access')" style="width: 190px">
          <template #body="{ data }">
            <span class="session-cell">{{ formatSessionDate(data.last_connection_date) }}</span>
          </template>
        </Column>
        <Column :header="t('ui.account.sessions.ip')">
          <template #body="{ data }">
            <code class="session-ip">{{ data.ip || '—' }}</code>
          </template>
        </Column>
        <template #empty>
          <span class="text-sm text-muted">{{ t('ui.account.sessions.none') }}</span>
        </template>
      </DataTable>
    </template></Card>
  </div>
</template>

<style scoped>
.section-title {
  margin: 0 0 0.75rem;
  font-size: 0.9375rem;
  font-weight: 600;
}
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
.sessions-card {
  max-width: 720px;
}
.sessions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}
.session-cell {
  font-size: 0.8125rem;
  white-space: nowrap;
}
.session-ip {
  font-family: monospace;
  font-size: 0.75rem;
}
</style>
