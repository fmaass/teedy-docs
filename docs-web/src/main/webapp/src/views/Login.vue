<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { requestPasswordReset } from '../api/user'
import api from '../api/client'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const auth = useAuthStore()
const toast = useToast()

const username = ref('')
const password = ref('')
const remember = ref(false)
const loading = ref(false)
const guestLoading = ref(false)
const error = ref('')

const oidcEnabled = ref(false)
const guestLogin = ref(false)
const oidcError = ref(false)

interface ApiError {
  response?: {
    data?: {
      message?: string
    }
  }
}

function extractLoginErrorMessage(error: unknown, fallback: string): string {
  return (error as ApiError).response?.data?.message || fallback
}

onMounted(async () => {
  try {
    const { data } = await api.get('/app')
    oidcEnabled.value = !!data.oidc_enabled
    guestLogin.value = !!data.guest_login
  } catch { /* non-critical — buttons just stay hidden */ }

  if (route.query.error) {
    oidcError.value = true
    return
  }

  if (oidcEnabled.value && !route.query.local) {
    handleOidcLogin()
  }
})

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value, remember.value)
    router.push({ name: 'documents' })
  } catch (loginError: unknown) {
    error.value = extractLoginErrorMessage(loginError, 'Invalid username or password')
  } finally {
    loading.value = false
  }
}

function handleOidcLogin() {
  const returnUrl = encodeURIComponent('/#/document')
  window.location.href = `api/oidc/login?returnUrl=${returnUrl}`
}

// Pin local login: sets ?local so the SSO auto-redirect is suppressed and the
// local account form is presented (also clears an SSO error query).
function useLocalAccount() {
  oidcError.value = false
  router.replace({ name: 'login', query: { local: '1' } })
}

async function handleGuestLogin() {
  error.value = ''
  guestLoading.value = true
  try {
    await auth.login('guest', '', false)
    router.push({ name: 'documents' })
  } catch (loginError: unknown) {
    error.value = extractLoginErrorMessage(loginError, 'Guest login failed')
  } finally {
    guestLoading.value = false
  }
}

// Forgot password
const showForgot = ref(false)
const forgotUsername = ref('')
const forgotLoading = ref(false)

async function handleForgot() {
  if (!forgotUsername.value.trim()) return
  forgotLoading.value = true
  try {
    await requestPasswordReset(forgotUsername.value.trim())
    showForgot.value = false
    forgotUsername.value = ''
    toast.add({ severity: 'info', summary: t('ui.forgot_password.sent'), life: 5000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.forgot_password.failed'), life: 3000 })
  } finally {
    forgotLoading.value = false
  }
}
</script>

<template>
  <div class="teedy-login">
    <div class="teedy-login-card">
      <div class="teedy-login-brand">
        <h1>teedy</h1>
        <p>{{ t('ui.document_management') }}</p>
      </div>

      <Message v-if="oidcError" severity="warn" :closable="false" class="mb-4">{{ t('ui.sso_failed') }}</Message>
      <Message v-if="error" severity="error" :closable="false" class="mb-4">{{ error }}</Message>

      <form @submit.prevent="handleLogin">
        <div class="teedy-login-field">
          <label for="login-user">{{ t('login.username') }}</label>
          <InputText
            id="login-user"
            v-model="username"
            autocomplete="username"
            class="w-full"
            autofocus
          />
        </div>

        <div class="teedy-login-field">
          <label for="login-pass">{{ t('login.password') }}</label>
          <Password
            inputId="login-pass"
            v-model="password"
            :feedback="false"
            toggleMask
            :inputProps="{ autocomplete: 'current-password', name: 'password' }"
            inputClass="w-full"
            class="w-full"
          />
        </div>

        <div class="teedy-login-row">
          <label class="flex items-center gap-2 text-sm">
            <Checkbox v-model="remember" :binary="true" />
            {{ t('login.remember_me') }}
          </label>
          <button type="button" class="forgot-link" @click="showForgot = true">
            {{ t('login.password_lost_btn') }}
          </button>
        </div>

        <Button
          type="submit"
          :label="t('login.submit')"
          icon="pi pi-sign-in"
          :loading="loading"
          class="w-full"
        />
      </form>

      <div v-if="guestLogin || oidcEnabled" class="login-alt-actions">
        <Button
          v-if="guestLogin"
          :label="t('login.login_as_guest')"
          icon="pi pi-user"
          severity="secondary"
          outlined
          class="w-full"
          :loading="guestLoading"
          @click="handleGuestLogin"
        />
        <Button
          v-if="oidcEnabled"
          :label="t('login.login_with_sso')"
          icon="pi pi-sign-in"
          severity="secondary"
          outlined
          class="w-full"
          @click="handleOidcLogin"
        />
      </div>

      <button
        v-if="oidcEnabled"
        type="button"
        class="local-account-link"
        @click="useLocalAccount"
      >
        {{ t('login.use_local_account') }}
      </button>
    </div>

    <!-- Forgot password dialog -->
    <Dialog v-model:visible="showForgot" :header="t('ui.forgot_password.title')" :style="{ width: '360px' }" modal>
      <p class="text-sm text-muted mb-3">
        {{ t('ui.forgot_password.message') }}
      </p>
      <InputText
        v-model="forgotUsername"
        :placeholder="t('ui.forgot_password.username_placeholder')"
        class="w-full"
        autofocus
        @keyup.enter="handleForgot"
      />
      <template #footer>
        <Button :label="t('cancel')" severity="secondary" text @click="showForgot = false" />
        <Button :label="t('ui.forgot_password.submit')" icon="pi pi-send" :loading="forgotLoading" @click="handleForgot" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.teedy-login-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.forgot-link {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.8125rem;
  color: var(--teedy-brand);
  padding: 0;
}
.forgot-link:hover {
  text-decoration: underline;
}

.login-alt-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--p-content-border-color);
}

.local-account-link {
  display: block;
  width: 100%;
  margin-top: 0.75rem;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.8125rem;
  color: var(--teedy-brand);
  text-align: center;
}
.local-account-link:hover {
  text-decoration: underline;
}
</style>
