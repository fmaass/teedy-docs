<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import { useToast } from 'primevue/usetoast'

const router = useRouter()
const auth = useAuthStore()
const toast = useToast()

const username = ref('')
const password = ref('')
const remember = ref(false)
const loading = ref(false)

async function handleLogin() {
  loading.value = true
  try {
    await auth.login(username.value, password.value, remember.value)
    router.push({ name: 'documents' })
  } catch (e: any) {
    toast.add({
      severity: 'error',
      summary: 'Login failed',
      detail: e.response?.data?.message || 'Invalid credentials',
      life: 3000,
    })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <h1>Teedy</h1>
        <p>Document Management System</p>
      </div>
      <form @submit.prevent="handleLogin" class="login-form">
        <div class="field">
          <label for="username">Username</label>
          <InputText
            id="username"
            v-model="username"
            autocomplete="username"
            :fluid="true"
          />
        </div>
        <div class="field">
          <label for="password">Password</label>
          <Password
            id="password"
            v-model="password"
            :feedback="false"
            toggleMask
            autocomplete="current-password"
            :fluid="true"
          />
        </div>
        <div class="field-checkbox">
          <Checkbox v-model="remember" inputId="remember" :binary="true" />
          <label for="remember">Remember me</label>
        </div>
        <Button
          type="submit"
          label="Sign in"
          :loading="loading"
          :fluid="true"
        />
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--p-surface-50);
}
.login-card {
  width: 100%;
  max-width: 400px;
  padding: 2.5rem;
  background: var(--p-surface-0);
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}
.login-header {
  text-align: center;
  margin-bottom: 2rem;
}
.login-header h1 {
  margin: 0 0 0.25rem;
  font-size: 2rem;
  color: var(--p-primary-color);
}
.login-header p {
  margin: 0;
  color: var(--p-text-muted-color);
}
.login-form .field {
  margin-bottom: 1.25rem;
}
.login-form .field label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
}
.field-checkbox {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
}
</style>
