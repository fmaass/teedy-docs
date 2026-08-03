<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQueryClient } from '@tanstack/vue-query'
import { useAuthStore } from '../stores/auth'
import { useBrand } from '../composables/useThemeBranding'
import { requestPasswordReset } from '../api/user'
import { getAppInfo, type FooterLink } from '../api/app'
import { queryKeys } from '../api/queryKeys'
import ErrorState from '../components/ErrorState.vue'
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
const queryClient = useQueryClient()

const username = ref('')
const password = ref('')
const remember = ref(false)
const validationCode = ref('')
// Set true after the backend challenges a TOTP-enabled login with
// "ValidationCodeRequired": the OTP code field is revealed and the user re-submits.
// Stays false for every non-TOTP login, so the field never shows for them.
const totpRequired = ref(false)
const loading = ref(false)
const guestLoading = ref(false)
const error = ref('')

const oidcEnabled = ref(false)
const guestLogin = ref(false)
const oidcError = ref(false)
// Configurable footer/imprint links, rendered beneath the login card so EU imprint
// links are reachable BEFORE login (GET /app is anonymous). Empty by default.
const footerLinks = ref<FooterLink[]>([])

// #258: the admin-uploaded branding background. It is served by a PUBLIC endpoint and read from
// the shared theme query App.vue already mounts — so this page, which is itself public, adds no
// request and no authenticated call. Null until an admin actually uploads one.
const { brandBackgroundUrl } = useBrand()

// Only the URL is bound; the scrim that keeps the form legible over an arbitrary photo is
// composed in CSS, where it can differ per theme. Binding `undefined` (not an empty object) when
// there is no background means no style attribute is emitted at all, so the default login page
// stays byte-identical to what it rendered before this feature.
const backgroundStyle = computed(() =>
  brandBackgroundUrl.value
    ? { '--login-background-image': `url("${brandBackgroundUrl.value}")` }
    : undefined,
)

interface ApiError {
  response?: {
    status?: number
    data?: {
      type?: string
      message?: string
    }
  }
}

function extractLoginErrorMessage(error: unknown, fallback: string): string {
  return (error as ApiError).response?.data?.message || fallback
}

// The backend signals a TOTP-enabled account with a 400 whose JSON body carries
// type "ValidationCodeRequired" (UserResource#login). api/client.ts leaves this
// rejection intact (only 401 is intercepted), so the type is read straight off
// error.response.data. Require the 400 status too, so an unrelated failure that
// happened to echo the type string can't force the code prompt.
function isValidationCodeRequired(error: unknown): boolean {
  const res = (error as ApiError).response
  return res?.status === 400 && res?.data?.type === 'ValidationCodeRequired'
}

// A wrong TOTP code is a genuine 403 (ForbiddenClientException). Only this status
// is treated as "wrong code"; a network error, rate-limit (429) or any other
// failure falls through to normal error handling instead of being mislabeled.
function isForbidden(error: unknown): boolean {
  return (error as ApiError).response?.status === 403
}

// After a challenge, editing the username or password must retract the code prompt
// so a code entered for one account can't be submitted against a different one.
watch([username, password], () => {
  if (totpRequired.value) {
    totpRequired.value = false
    validationCode.value = ''
    error.value = ''
  }
})

onMounted(async () => {
  try {
    // Shared app-info cache/key so a later authed screen reuses this fetch.
    const data = await queryClient.fetchQuery({ queryKey: queryKeys.app(), queryFn: () => getAppInfo() })
    oidcEnabled.value = !!data.oidc_enabled
    guestLogin.value = !!data.guest_login
    footerLinks.value = data.footer_links ?? []
  } catch { /* non-critical — buttons just stay hidden */ }

  if (route.query.error) {
    oidcError.value = true
    return
  }

  // #245: never hand the browser to the IdP while the backend is unreachable. The redirect would
  // replace the outage surface with an SSO round-trip that comes back to a still-broken /api/user —
  // i.e. the outage would be invisible, and a provider that keeps its own session would bounce
  // straight back here and start the loop again.
  if (oidcEnabled.value && !route.query.local && !auth.serverUnavailable) {
    handleOidcLogin()
  }
})

// #245 Retry: re-ask the server who the current user is. A recovered server with a live session
// lands on the documents list (the navigation the outage interrupted); a recovered server with no
// session clears the outage flag, and the ordinary credential form renders in place of this surface.
const availabilityRetrying = ref(false)

async function handleAvailabilityRetry() {
  if (availabilityRetrying.value) return
  availabilityRetrying.value = true
  try {
    await auth.fetchCurrentUser()
    if (!auth.serverUnavailable && !auth.isAnonymous) {
      router.push({ name: 'documents' })
    }
  } finally {
    availabilityRetrying.value = false
  }
}

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(
      username.value,
      password.value,
      remember.value,
      totpRequired.value ? validationCode.value : undefined,
    )
    router.push({ name: 'documents' })
  } catch (loginError: unknown) {
    if (isValidationCodeRequired(loginError)) {
      // TOTP-enabled account: reveal the code field and let the user re-submit.
      // Password was accepted; only the OTP code is outstanding.
      totpRequired.value = true
      error.value = t('login.validation_code_required')
    } else if (totpRequired.value && isForbidden(loginError)) {
      // Code field is showing and the backend returned 403 — the OTP code was
      // wrong. Clear it, keep the field visible for a retry, show a wrong-code msg.
      validationCode.value = ''
      error.value = t('login.validation_code_invalid')
    } else {
      // Any other failure (bad password before challenge, network error, 429
      // rate-limit, etc.) uses the backend message / generic fallback.
      error.value = extractLoginErrorMessage(loginError, 'Invalid username or password')
    }
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
  <div
    class="teedy-login login-page"
    :class="{ 'has-login-background': !!brandBackgroundUrl }"
    :style="backgroundStyle"
  >
    <div class="teedy-login-card">
      <div class="teedy-login-brand">
        <h1>teedy</h1>
        <p>{{ t('ui.document_management') }}</p>
      </div>

      <!--
        #245: the backend could not say who the current user is. Showing the credential form here
        would be a lie — it invites a sign-in that cannot succeed, and it is indistinguishable from
        having been logged out. The shared ErrorState (icon + message + Retry) says the honest thing
        instead, and the form comes back the moment the server answers again.
      -->
      <ErrorState v-if="auth.serverUnavailable" class="login-unavailable" @retry="handleAvailabilityRetry" />

      <template v-else>
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

          <div v-if="totpRequired" class="teedy-login-field">
            <label for="login-code">{{ t('login.validation_code') }}</label>
            <p class="text-sm text-muted mb-2">{{ t('login.validation_code_title') }}</p>
            <InputText
              id="login-code"
              v-model="validationCode"
              inputmode="numeric"
              autocomplete="one-time-code"
              class="w-full"
              autofocus
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
      </template>
    </div>

    <div v-if="footerLinks.length" class="teedy-login-footer">
      <a
        v-for="(link, index) in footerLinks"
        :key="index"
        :href="link.url"
        target="_blank"
        rel="noopener noreferrer"
      >{{ link.label }}</a>
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
/* ── #258: the admin-uploaded branding background ──
   Every rule here is gated on .has-login-background, which the view sets ONLY when the theme
   reports background_version > 0 — i.e. only when an admin actually uploaded a file. The image
   endpoint also serves a bundled default, so an install that never chose a background matches
   none of these selectors and renders exactly as it did before. */
.login-page.has-login-background {
  /* The scrim is a second background LAYER rather than an overlay element: layers paint
     front-to-back, so the gradient sits on top of the photo without introducing a stacking
     context, a pseudo-element or any layout change. The base colour from .teedy-login still
     shows through until the image has loaded, so there is no flash. */
  background-image:
    linear-gradient(var(--login-scrim), var(--login-scrim)),
    var(--login-background-image);
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  /* Dark in BOTH themes on purpose. A theme-following scrim would be light-on-light for a bright
     image in light mode; a consistently dark one puts every image — bright, busy or dark — behind
     the same predictable field, which is what lets the text colours below be fixed. */
  --login-scrim: rgba(0, 0, 0, 0.5);
}
.dark-mode .login-page.has-login-background {
  --login-scrim: rgba(0, 0, 0, 0.68);
}

/* The card is what actually keeps the form legible: it is an opaque surface
   (--p-content-background), so the username/password fields, labels, button and error message
   never have the photo behind them. This only separates its edge from a busy image. */
.login-page.has-login-background .teedy-login-card {
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.45);
}

/* The footer/imprint links are the only text that sits directly on the page background instead of
   inside the card, so they get their own surface rather than borrowing the card's.

   Measured on a real instance (white 12px links, page scrim only): 5.70:1 worst-pixel over a
   bright photograph and 10.03:1 over a dark one, but 4.35:1 over a near-white image — under the
   4.5:1 AA bar, with an analytic floor of 3.95:1 for a pure-white pixel. A bright sky or a
   white-backed product shot reaches that, so the fix is a plate the links carry with them
   instead of a heavier page scrim, which would darken the whole photo for everyone.
   Over the 0.5 page scrim this holds the worst case near 9:1 whatever the image does.

   `width: auto` overrides the full-width footer box so the plate hugs the links and centres
   with them, rather than drawing a card-width bar under the form. */
.login-page.has-login-background .teedy-login-footer {
  width: auto;
  padding: 0.3rem 0.9rem;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 999px;
}

.login-page.has-login-background .teedy-login-footer a {
  color: #fff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.65);
}

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

.teedy-login-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 0.5rem 1rem;
  width: 100%;
  max-width: 420px;
  margin-top: 1.25rem;
  text-align: center;
}
.teedy-login-footer a {
  font-size: 0.75rem;
  color: var(--teedy-brand);
  text-decoration: none;
}
.teedy-login-footer a:hover {
  text-decoration: underline;
}
</style>
