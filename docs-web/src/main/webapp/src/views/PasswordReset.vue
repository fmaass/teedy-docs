<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { resetPassword } from '../api/user'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'

const props = defineProps<{ resetKey: string }>()
const router = useRouter()
const { t } = useI18n()
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
    error.value = t('ui.password_reset.password_required')
    return
  }
  if (password.value.length < 8) {
    error.value = t('ui.password_reset.password_min_length')
    return
  }
  if (password.value !== passwordConfirm.value) {
    error.value = t('ui.password_reset.passwords_mismatch')
    return
  }
  loading.value = true
  try {
    await resetPassword(props.resetKey, password.value)
    toast.add({ severity: 'success', summary: t('ui.password_reset.password_changed'), life: 5000 })
    router.push({ name: 'login' })
  } catch (errorResponse: unknown) {
    const type = (errorResponse as PasswordResetError).response?.data?.type
    if (type === 'KeyNotFound') {
      error.value = t('ui.password_reset.link_expired')
    } else {
      error.value = t('ui.password_reset.failed')
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
        <p>{{ t('ui.password_reset.set_new_password') }}</p>
      </div>

      <Message v-if="error" severity="error" :closable="false" class="mb-4">{{ error }}</Message>

      <form @submit.prevent="handleReset">
        <div class="teedy-login-field">
          <label for="reset-pass">{{ t('ui.password_reset.new_password') }}</label>
          <Password
            inputId="reset-pass"
            v-model="password"
            :feedback="true"
            toggleMask
            :inputProps="{ autocomplete: 'new-password', name: 'new-password' }"
            inputClass="w-full"
            class="w-full"
            autofocus
          />
        </div>

        <div class="teedy-login-field">
          <label for="reset-confirm">{{ t('ui.password_reset.confirm_password') }}</label>
          <Password
            inputId="reset-confirm"
            v-model="passwordConfirm"
            :feedback="false"
            toggleMask
            :inputProps="{ autocomplete: 'new-password', name: 'confirm-password' }"
            inputClass="w-full"
            class="w-full"
          />
        </div>

        <Button
          type="submit"
          :label="t('ui.password_reset.submit')"
          icon="pi pi-check"
          :loading="loading"
          class="w-full"
        />

        <div class="back-link">
          <router-link :to="{ name: 'login' }">{{ t('ui.password_reset.back_to_login') }}</router-link>
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
