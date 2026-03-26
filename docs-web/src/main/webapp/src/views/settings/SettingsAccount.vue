<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import api from '../../api/client'

const auth = useAuthStore()
const toast = useToast()

const password = ref('')
const passwordConfirm = ref('')
const saving = ref(false)

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
</script>

<template>
  <div>
    <h2>User account</h2>
    <p class="text-sm text-muted mb-4">
      Logged in as <strong>{{ auth.username }}</strong>
      <span v-if="auth.user?.email"> ({{ auth.user.email }})</span>
    </p>

    <div class="teedy-card p-4" style="max-width: 400px">
      <div class="form-field">
        <label>New password</label>
        <Password v-model="password" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
      </div>
      <div class="form-field">
        <label>Confirm password</label>
        <Password v-model="passwordConfirm" :feedback="false" toggleMask inputClass="w-full" class="w-full" />
      </div>
      <Button label="Save" icon="pi pi-check" :loading="saving" @click="handleSave" />
    </div>
  </div>
</template>

<style scoped>
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
