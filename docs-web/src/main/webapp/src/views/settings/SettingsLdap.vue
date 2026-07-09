<script setup lang="ts">
import { reactive, ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation } from '@tanstack/vue-query'
import { getLdapConfig, saveLdapConfig, type LdapConfig } from '../../api/ldap'
import { validateLdapConfig } from '../../utils/ldapValidation'
import Card from 'primevue/card'
import ToggleSwitch from 'primevue/toggleswitch'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Password from 'primevue/password'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'

const { t } = useI18n()
const toast = useToast()

const form = reactive<LdapConfig>({
  enabled: false,
  host: '',
  port: 389,
  usessl: false,
  admin_dn: '',
  admin_password: '',
  base_dn: '',
  filter: '(&(objectClass=user)(sAMAccountName=USERNAME))',
  default_email: '',
  default_storage: 104857600,
})

const { data: ldapConfig, isLoading } = useQuery({
  queryKey: ['ldap-config'],
  queryFn: () => getLdapConfig(),
})

// Seed the form once from the server. `enabled: false` responses carry no other
// fields, so keep the sensible template defaults for a first-time setup.
let seeded = false
watch(ldapConfig, (config) => {
  if (!config || seeded) return
  form.enabled = config.enabled === true
  if (config.enabled) {
    form.host = config.host ?? ''
    form.port = config.port ?? 389
    form.usessl = config.usessl ?? false
    form.admin_dn = config.admin_dn ?? ''
    form.admin_password = config.admin_password ?? ''
    form.base_dn = config.base_dn ?? ''
    form.filter = config.filter ?? form.filter
    form.default_email = config.default_email ?? ''
    form.default_storage = config.default_storage ?? form.default_storage
  }
  seeded = true
}, { immediate: true })

const fieldErrors = ref<Set<string>>(new Set())

const storageMb = computed(() =>
  form.default_storage ? Math.round(form.default_storage / (1024 * 1024)) : 0,
)

const { mutate: save, isPending: saving } = useMutation({
  mutationFn: () => saveLdapConfig({ ...form }),
  onSuccess: () => {
    toast.add({ severity: 'success', summary: t('ui.ldap.config_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.ldap.failed_save'), life: 3000 })
  },
})

function onSave() {
  const errors = validateLdapConfig({ ...form })
  fieldErrors.value = new Set(errors)
  if (errors.length > 0) {
    toast.add({ severity: 'error', summary: t('ui.ldap.validation_failed'), life: 3000 })
    return
  }
  save()
}
</script>

<template>
  <div>
    <h2>{{ t('ui.ldap.title') }}</h2>
    <p class="section-hint">{{ t('ui.ldap.description') }}</p>

    <Card class="mb-4" style="max-width: 560px"><template #content>
      <div class="enable-toggle">
        <ToggleSwitch v-model="form.enabled" inputId="ldap-enabled" :disabled="isLoading" />
        <span>{{ form.enabled ? t('ui.ldap.enabled') : t('ui.ldap.disabled') }}</span>
      </div>

      <template v-if="form.enabled">
        <div class="form-field">
          <label for="ldap-host">{{ t('ui.ldap.host') }}</label>
          <InputText id="ldap-host" v-model="form.host" class="w-full" :invalid="fieldErrors.has('host_required')" />
          <small v-if="fieldErrors.has('host_required')" class="field-error">{{ t('ui.ldap.host_required') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-port">{{ t('ui.ldap.port') }}</label>
          <InputNumber id="ldap-port" v-model="form.port" :useGrouping="false" class="w-full" :invalid="fieldErrors.has('port_required')" />
          <small v-if="fieldErrors.has('port_required')" class="field-error">{{ t('ui.ldap.port_required') }}</small>
        </div>

        <div class="form-field">
          <div class="ssl-toggle">
            <ToggleSwitch v-model="form.usessl" inputId="ldap-ssl" />
            <span>{{ t('ui.ldap.usessl') }}</span>
          </div>
        </div>

        <div class="form-field">
          <label for="ldap-admin-dn">{{ t('ui.ldap.admin_dn') }}</label>
          <InputText id="ldap-admin-dn" v-model="form.admin_dn" class="w-full" :invalid="fieldErrors.has('admin_dn_required')" />
          <small v-if="fieldErrors.has('admin_dn_required')" class="field-error">{{ t('ui.ldap.admin_dn_required') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-admin-password">{{ t('ui.ldap.admin_password') }}</label>
          <Password inputId="ldap-admin-password" v-model="form.admin_password" :feedback="false" toggleMask :inputProps="{ autocomplete: 'off', name: 'ldap-admin-password' }" :invalid="fieldErrors.has('admin_password_required')" inputClass="w-full" class="w-full" />
          <small v-if="fieldErrors.has('admin_password_required')" class="field-error">{{ t('ui.ldap.admin_password_required') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-base-dn">{{ t('ui.ldap.base_dn') }}</label>
          <InputText id="ldap-base-dn" v-model="form.base_dn" class="w-full" :invalid="fieldErrors.has('base_dn_required')" />
          <small v-if="fieldErrors.has('base_dn_required')" class="field-error">{{ t('ui.ldap.base_dn_required') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-filter">{{ t('ui.ldap.filter') }}</label>
          <InputText id="ldap-filter" v-model="form.filter" class="w-full" :invalid="fieldErrors.has('filter_username') || fieldErrors.has('filter_required')" />
          <small class="field-hint">{{ t('ui.ldap.filter_hint') }}</small>
          <small v-if="fieldErrors.has('filter_required')" class="field-error">{{ t('ui.ldap.filter_required') }}</small>
          <small v-else-if="fieldErrors.has('filter_username')" class="field-error">{{ t('ui.ldap.filter_username_error') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-default-email">{{ t('ui.ldap.default_email') }}</label>
          <InputText id="ldap-default-email" v-model="form.default_email" class="w-full" :invalid="fieldErrors.has('default_email_required')" />
          <small class="field-hint">{{ t('ui.ldap.default_email_hint') }}</small>
          <small v-if="fieldErrors.has('default_email_required')" class="field-error">{{ t('ui.ldap.default_email_required') }}</small>
        </div>

        <div class="form-field">
          <label for="ldap-default-storage">{{ t('ui.ldap.default_storage') }}</label>
          <InputNumber id="ldap-default-storage" v-model="form.default_storage" :useGrouping="true" suffix=" B" class="w-full" :min="0" :invalid="fieldErrors.has('default_storage_required')" />
          <small class="field-hint">{{ t('ui.ldap.default_storage_hint', { mb: storageMb }) }}</small>
          <small v-if="fieldErrors.has('default_storage_required')" class="field-error">{{ t('ui.ldap.default_storage_required') }}</small>
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
  max-width: 560px;
}
.enable-toggle {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
  margin-bottom: 1.25rem;
}
.ssl-toggle {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
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
.field-error {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-red-500);
}
.w-full {
  width: 100%;
}
.save-btn {
  margin-top: 0.5rem;
}
</style>
