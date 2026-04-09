<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { resetPassword } from '../api/user'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ resetKey: string }>()
const router = useRouter()
const toast = useToast()

const password = ref('')
const passwordConfirm = ref('')
const loading = ref(false)
const error = ref('')

interface PasswordResetError {
  response?: {
    data?: {
      type?: string
    }
  }
}

async function handleReset() {
  error.value = ''
  if (!password.value) {
    error.value = 'Password is required'
    return
  }
  if (password.value.length < 8) {
    error.value = 'Password must be at least 8 characters'
    return
  }
  if (password.value !== passwordConfirm.value) {
    error.value = 'Passwords do not match'
    return
  }
  loading.value = true
  try {
    await resetPassword(props.resetKey, password.value)
    toast.add({ severity: 'success', summary: 'Password changed. You can now sign in.', life: 5000 })
    router.push({ name: 'login' })
  } catch (errorResponse: unknown) {
    const type = (errorResponse as PasswordResetError).response?.data?.type
    if (type === 'KeyNotFound') {
      error.value = 'This reset link has expired or is invalid. Please request a new one.'
    } else {
      error.value = 'Failed to reset password. Please try again.'
    }
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
        <p>Set new password</p>
      </div>

      <Message v-if="error" severity="error" :closable="false" class="mb-4">{{ error }}</Message>

      <form @submit.prevent="handleReset">
        <div class="teedy-login-field">
          <label for="reset-pass">New password</label>
          <Password
            id="reset-pass"
            v-model="password"
            :feedback="true"
            toggleMask
            inputClass="w-full"
            class="w-full"
            autofocus
          />
        </div>

        <div class="teedy-login-field">
          <label for="reset-confirm">Confirm new password</label>
          <Password
            id="reset-confirm"
            v-model="passwordConfirm"
            :feedback="false"
            toggleMask
            inputClass="w-full"
            class="w-full"
          />
        </div>

        <Button
          type="submit"
          label="Set new password"
          icon="pi pi-check"
          :loading="loading"
          class="w-full"
        />

        <div class="back-link">
          <router-link :to="{ name: 'login' }">Back to sign in</router-link>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.back-link {
  text-align: center;
  margin-top: 1rem;
  font-size: 0.875rem;
}
.back-link a {
  color: var(--teedy-brand);
  text-decoration: none;
}
.back-link a:hover {
  text-decoration: underline;
}
</style>
