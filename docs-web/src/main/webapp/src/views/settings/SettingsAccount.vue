<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { setLocale } from '../../i18n'
import { useThemeSwitch, themeNames, getStoredTheme } from '../../composables/useThemeSwitch'
import Password from 'primevue/password'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import Button from 'primevue/button'
import Card from 'primevue/card'
import { useToast } from 'primevue/usetoast'
import api from '../../api/client'

const auth = useAuthStore()
const toast = useToast()
const { switchTheme } = useThemeSwitch()

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
const darkMode = ref(document.documentElement.classList.contains('dark-mode'))

onMounted(() => {
  selectedLocale.value = localStorage.getItem('teedy-locale') || 'en'
})

async function handleSave() {
  if (password.value !== passwordConfirm.value) {
    toast.add({ severity: 'warn', summary: 'Passwords do not match', life: 2000 })
    return
  }
  if (!password.value) return

  saving.value = true
  try {
    const params = new URLSearchParams()
    params.set('password', password.value)
    await api.post('/user', params)
    password.value = ''
    passwordConfirm.value = ''
    toast.add({ severity: 'success', summary: 'Password updated', life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to update password', life: 3000 })
  } finally {
    saving.value = false
  }
}

async function handleLocaleChange(locale: string) {
  await setLocale(locale)
  localStorage.setItem('teedy-locale', locale)
  toast.add({ severity: 'success', summary: 'Language updated', life: 2000 })
}

function handleThemeChange(name: string) {
  switchTheme(name)
  toast.add({ severity: 'success', summary: `Theme switched to ${name}`, life: 2000 })
}

function handleDarkModeToggle(val: boolean) {
  darkMode.value = val
  if (val) {
    document.documentElement.classList.add('dark-mode')
  } else {
    document.documentElement.classList.remove('dark-mode')
  }
  localStorage.setItem('teedy-dark-mode', String(val))
}
</script>

<template>
  <div>
    <h2>User account</h2>
    <p class="text-sm text-muted mb-4">
      Logged in as <strong>{{ auth.username }}</strong>
      <span v-if="auth.user?.email"> ({{ auth.user.email }})</span>
    </p>

    <!-- Appearance -->
    <Card class="mb-3" style="max-width: 400px"><template #content>
      <h3 class="section-title">Appearance</h3>
      <div class="form-field">
        <label>Theme</label>
        <Select
          v-model="selectedTheme"
          :options="themeOptions"
          optionLabel="label"
          optionValue="value"
          class="w-full"
          @change="(e: any) => handleThemeChange(e.value)"
        />
      </div>
      <div class="form-field">
        <label class="toggle-label">
          <ToggleSwitch :modelValue="darkMode" @update:modelValue="handleDarkModeToggle" />
          Dark mode
        </label>
      </div>
    </template></Card>

    <!-- Language -->
    <Card class="mb-3" style="max-width: 400px"><template #content>
      <h3 class="section-title">Language</h3>
      <Select
        v-model="selectedLocale"
        :options="languages"
        optionLabel="label"
        optionValue="value"
        class="w-full"
        @change="(e: any) => handleLocaleChange(e.value)"
      />
    </template></Card>

    <!-- Password -->
    <Card style="max-width: 400px"><template #content>
      <h3 class="section-title">Change password</h3>
      <div class="form-field">
        <label>New password</label>
        <Password v-model="password" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
      </div>
      <div class="form-field">
        <label>Confirm password</label>
        <Password v-model="passwordConfirm" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
      </div>
      <Button label="Save" icon="pi pi-check" :loading="saving" @click="handleSave" />
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
.toggle-label {
  display: flex !important;
  align-items: center;
  gap: 0.75rem;
}
</style>
