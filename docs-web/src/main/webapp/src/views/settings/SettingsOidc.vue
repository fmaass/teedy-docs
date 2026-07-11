<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation } from '@tanstack/vue-query'
import { getOidcConfig, saveOidcConfig, type OidcConfig, type OidcSource } from '../../api/oidc'
import { validateOidcConfig } from '../../utils/oidcValidation'
import Card from 'primevue/card'
import ToggleSwitch from 'primevue/toggleswitch'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const { t } = useI18n()
const toast = useToast()

const form = reactive<OidcConfig>({
  enabled: false,
  issuer: '',
  client_id: '',
  client_secret: '',
  client_secret_set: false,
  client_secret_reset: false,
  redirect_uri: '',
  scope: 'openid profile email',
  authorization_endpoint: '',
  token_endpoint: '',
  jwks_uri: '',
  userinfo_endpoint: '',
  username_claim: 'preferred_username',
  email_claim: 'email',
})

const sources = ref<Record<string, OidcSource>>({})

const { data: oidcConfig, isLoading, refetch } = useQuery({
  queryKey: ['oidc-config'],
  queryFn: () => getOidcConfig(),
})

// Seed the form once from the server. The client secret is write-only: never populate it from
// the server; keep it blank and let client_secret_set drive the "leave blank to keep" affordance.
let seeded = false
watch(oidcConfig, (config) => {
  if (!config || seeded) return
  form.enabled = config.enabled === true
  form.issuer = config.issuer ?? ''
  form.client_id = config.client_id ?? ''
  form.client_secret = ''
  form.client_secret_set = config.client_secret_set ?? false
  form.client_secret_reset = false
  form.redirect_uri = config.redirect_uri ?? ''
  form.scope = config.scope || 'openid profile email'
  form.authorization_endpoint = config.authorization_endpoint ?? ''
  form.token_endpoint = config.token_endpoint ?? ''
  form.jwks_uri = config.jwks_uri ?? ''
  form.userinfo_endpoint = config.userinfo_endpoint ?? ''
  form.username_claim = config.username_claim || 'preferred_username'
  form.email_claim = config.email_claim || 'email'
  sources.value = config.sources ?? {}
  seeded = true
}, { immediate: true })

const fieldErrors = ref<Set<string>>(new Set())

// A field whose effective value currently comes from a JVM property: saving stores a DB override.
function isFromProperty(key: string): boolean {
  return sources.value[key] === 'property'
}

const { mutate: save, isPending: saving } = useMutation({
  mutationFn: () => saveOidcConfig({ ...form }),
  onSuccess: () => {
    // Never keep the entered secret in the reactive form after a successful save: it would be
    // revealable via the toggle and resent verbatim on the next save. Clear it and re-seed from
    // the server so client_secret_set drives the masked "leave blank to keep" placeholder again.
    form.client_secret = ''
    form.client_secret_reset = false
    // A save may flip a field's source to db; re-seed from the refreshed server config.
    seeded = false
    refetch()
    toast.add({ severity: 'success', summary: t('ui.oidc.config_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.oidc.failed_save'), life: 3000 })
  },
})

function onSave() {
  const errors = validateOidcConfig({ ...form })
  fieldErrors.value = new Set(errors)
  if (errors.length > 0) {
    toast.add({ severity: 'error', summary: t('ui.oidc.validation_failed'), life: 3000 })
    return
  }
  save()
}

// Clearing the stored secret is a write-only reset: set the flag and blank the field.
function resetSecret() {
  form.client_secret = ''
  form.client_secret_reset = true
  form.client_secret_set = false
}
</script>

<template>
  <div>
    <h2>{{ t('ui.oidc.title') }}</h2>
    <p class="section-hint">{{ t('ui.oidc.description') }}</p>

    <Card class="mb-4" style="max-width: 620px"><template #content>
      <div class="enable-toggle">
        <ToggleSwitch v-model="form.enabled" inputId="oidc-enabled" :disabled="isLoading" />
        <span>{{ form.enabled ? t('ui.oidc.enabled') : t('ui.oidc.disabled') }}</span>
      </div>

      <template v-if="form.enabled">
        <div class="form-field">
          <label for="oidc-issuer">{{ t('ui.oidc.issuer') }}</label>
          <InputText id="oidc-issuer" v-model="form.issuer" class="w-full" :invalid="fieldErrors.has('issuer_required') || fieldErrors.has('issuer_url')" />
          <small v-if="isFromProperty('issuer')" class="field-hint source-hint">{{ t('ui.oidc.from_property') }}</small>
          <small v-if="fieldErrors.has('issuer_required')" class="field-error">{{ t('ui.oidc.issuer_required') }}</small>
          <small v-else-if="fieldErrors.has('issuer_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-client-id">{{ t('ui.oidc.client_id') }}</label>
          <InputText id="oidc-client-id" v-model="form.client_id" class="w-full" :invalid="fieldErrors.has('client_id_required')" />
          <small v-if="isFromProperty('client_id')" class="field-hint source-hint">{{ t('ui.oidc.from_property') }}</small>
          <small v-if="fieldErrors.has('client_id_required')" class="field-error">{{ t('ui.oidc.client_id_required') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-client-secret">{{ t('ui.oidc.client_secret') }}</label>
          <Password inputId="oidc-client-secret" v-model="form.client_secret" :feedback="false" toggleMask :inputProps="{ autocomplete: 'off', name: 'oidc-client-secret' }" :placeholder="form.client_secret_set ? t('ui.oidc.client_secret_keep') : ''" :invalid="fieldErrors.has('client_secret_required')" inputClass="w-full" class="w-full" />
          <small v-if="form.client_secret_set" class="field-hint">
            {{ t('ui.oidc.client_secret_keep') }}
            <Button :label="t('ui.oidc.client_secret_clear')" text size="small" class="clear-secret-btn" @click="resetSecret" />
          </small>
          <small v-else-if="isFromProperty('client_secret')" class="field-hint source-hint">{{ t('ui.oidc.from_property') }}</small>
          <small v-if="fieldErrors.has('client_secret_required')" class="field-error">{{ t('ui.oidc.client_secret_required') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-redirect-uri">{{ t('ui.oidc.redirect_uri') }}</label>
          <InputText id="oidc-redirect-uri" v-model="form.redirect_uri" class="w-full" :invalid="fieldErrors.has('redirect_uri_required') || fieldErrors.has('redirect_uri_url')" />
          <small class="field-hint">{{ t('ui.oidc.redirect_uri_hint') }}</small>
          <small v-if="isFromProperty('redirect_uri')" class="field-hint source-hint">{{ t('ui.oidc.from_property') }}</small>
          <small v-if="fieldErrors.has('redirect_uri_required')" class="field-error">{{ t('ui.oidc.redirect_uri_required') }}</small>
          <small v-else-if="fieldErrors.has('redirect_uri_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-scope">{{ t('ui.oidc.scope') }}</label>
          <InputText id="oidc-scope" v-model="form.scope" class="w-full" :invalid="fieldErrors.has('scope_required')" />
          <small v-if="fieldErrors.has('scope_required')" class="field-error">{{ t('ui.oidc.scope_required') }}</small>
        </div>

        <p class="subsection-hint">{{ t('ui.oidc.endpoints_hint') }}</p>

        <div class="form-field">
          <label for="oidc-auth-endpoint">{{ t('ui.oidc.authorization_endpoint') }}</label>
          <InputText id="oidc-auth-endpoint" v-model="form.authorization_endpoint" class="w-full" :invalid="fieldErrors.has('authorization_endpoint_url')" />
          <small v-if="fieldErrors.has('authorization_endpoint_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-token-endpoint">{{ t('ui.oidc.token_endpoint') }}</label>
          <InputText id="oidc-token-endpoint" v-model="form.token_endpoint" class="w-full" :invalid="fieldErrors.has('token_endpoint_url')" />
          <small v-if="fieldErrors.has('token_endpoint_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-jwks-uri">{{ t('ui.oidc.jwks_uri') }}</label>
          <InputText id="oidc-jwks-uri" v-model="form.jwks_uri" class="w-full" :invalid="fieldErrors.has('jwks_uri_url')" />
          <small v-if="fieldErrors.has('jwks_uri_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-userinfo-endpoint">{{ t('ui.oidc.userinfo_endpoint') }}</label>
          <InputText id="oidc-userinfo-endpoint" v-model="form.userinfo_endpoint" class="w-full" :invalid="fieldErrors.has('userinfo_endpoint_url')" />
          <small v-if="fieldErrors.has('userinfo_endpoint_url')" class="field-error">{{ t('ui.oidc.url_invalid') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-username-claim">{{ t('ui.oidc.username_claim') }}</label>
          <InputText id="oidc-username-claim" v-model="form.username_claim" class="w-full" :invalid="fieldErrors.has('username_claim_required')" />
          <small v-if="fieldErrors.has('username_claim_required')" class="field-error">{{ t('ui.oidc.username_claim_required') }}</small>
        </div>

        <div class="form-field">
          <label for="oidc-email-claim">{{ t('ui.oidc.email_claim') }}</label>
          <InputText id="oidc-email-claim" v-model="form.email_claim" class="w-full" :invalid="fieldErrors.has('email_claim_required')" />
          <small v-if="fieldErrors.has('email_claim_required')" class="field-error">{{ t('ui.oidc.email_claim_required') }}</small>
        </div>
      </template>

      <Button class="save-btn" :label="t('save')" icon="pi pi-check" :loading="saving" @click="onSave" />
    </template></Card>
  </div>
</template>

<style scoped>
.section-hint {
  margin: 0 0 1.25rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  line-height: 1.5;
  max-width: 620px;
}
.subsection-hint {
  margin: 1.25rem 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  line-height: 1.5;
}
.enable-toggle {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
  margin-bottom: 1.25rem;
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
.field-hint {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}
.source-hint {
  color: var(--p-primary-color);
}
.field-error {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-red-500);
}
.clear-secret-btn {
  padding: 0 0.25rem;
  font-size: 0.75rem;
}
.w-full {
  width: 100%;
}
.save-btn {
  margin-top: 0.5rem;
}
</style>
