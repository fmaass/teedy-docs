<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'

const router = useRouter()
const auth = useAuthStore()

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
  </div>
</template>
