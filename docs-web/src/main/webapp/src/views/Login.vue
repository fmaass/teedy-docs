<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { requestPasswordReset } from '../api/user'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'

const router = useRouter()
const auth = useAuthStore()
const toast = useToast()

const username = ref('')
const password = ref('')
const remember = ref(false)
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value, remember.value)
    router.push({ name: 'documents' })
  } catch (e: any) {
    error.value = e.response?.data?.message || 'Invalid username or password'
  } finally {
    loading.value = false
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
    toast.add({ severity: 'info', summary: 'If this username exists, a password reset email has been sent.', life: 5000 })
  } catch {
    toast.add({ severity: 'error', summary: 'Failed to send reset email', life: 3000 })
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
        <p>Document Management</p>
      </div>

      <Message v-if="error" severity="error" :closable="false" class="mb-4">{{ error }}</Message>

      <form @submit.prevent="handleLogin">
        <div class="teedy-login-field">
          <label for="login-user">Username</label>
          <InputText
            id="login-user"
            v-model="username"
            autocomplete="username"
            class="w-full"
            autofocus
          />
        </div>

        <div class="teedy-login-field">
          <label for="login-pass">Password</label>
          <Password
            id="login-pass"
            v-model="password"
            :feedback="false"
            toggleMask
            autocomplete="current-password"
            inputClass="w-full"
            class="w-full"
          />
        </div>

        <div class="teedy-login-row">
          <label class="flex items-center gap-2 text-sm">
            <Checkbox v-model="remember" :binary="true" />
            Remember me
          </label>
          <button type="button" class="forgot-link" @click="showForgot = true">
            Forgot password?
          </button>
        </div>

        <Button
          type="submit"
          label="Sign in"
          icon="pi pi-sign-in"
          :loading="loading"
          class="w-full"
        />
      </form>
    </div>

    <!-- Forgot password dialog -->
    <Dialog v-model:visible="showForgot" header="Reset password" :style="{ width: '360px' }" modal>
      <p class="text-sm text-muted mb-3">
        Enter your username to receive a password reset link by email.
      </p>
      <InputText
        v-model="forgotUsername"
        placeholder="Username"
        class="w-full"
        autofocus
        @keyup.enter="handleForgot"
      />
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="showForgot = false" />
        <Button label="Send reset link" icon="pi pi-send" :loading="forgotLoading" @click="handleForgot" />
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
</style>
