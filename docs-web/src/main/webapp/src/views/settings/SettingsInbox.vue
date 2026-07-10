<script setup lang="ts">
import { reactive, ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useMutation } from '@tanstack/vue-query'
import { getInboxConfig, saveInboxConfig, testInbox, type InboxConfig } from '../../api/app'
import { isSaveAndTestDisabled, runSaveThenTest, SaveThenTestError } from '../../utils/inboxTestFlow'
import { useInboxTestResult } from '../../composables/useInboxTestResult'
import Card from 'primevue/card'
import ToggleSwitch from 'primevue/toggleswitch'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'

const { t } = useI18n()
const toast = useToast()

// The IMAP password is write-only: the GET never returns it and there is no
// password_set flag. Keep the field blank and show a "leave blank to keep" hint.
const form = reactive<InboxConfig>({
  enabled: false,
  autoTagsEnabled: false,
  deleteImported: false,
  starttls: true,
  hostname: '',
  port: 993,
  username: '',
  password: '',
  folder: 'INBOX',
  tag: '',
})

const { data: inboxConfig, isLoading } = useQuery({
  queryKey: ['inbox-config'],
  queryFn: () => getInboxConfig(),
})

let seeded = false
watch(inboxConfig, (config) => {
  if (!config || seeded) return
  form.enabled = config.enabled === true
  form.autoTagsEnabled = config.autoTagsEnabled === true
  form.deleteImported = config.deleteImported === true
  form.starttls = config.starttls !== false
  form.hostname = config.hostname ?? ''
  form.port = config.port ?? 993
  form.username = config.username ?? ''
  form.password = ''
  form.folder = config.folder ?? 'INBOX'
  form.tag = config.tag ?? ''
  seeded = true
}, { immediate: true })

const { mutateAsync: saveAsync, isPending: saving } = useMutation({
  mutationFn: () => saveInboxConfig({ ...form }),
})

const testing = ref(false)
// The last test outcome, shown inline. Invariant: a shown result always describes
// the CURRENTLY-SAVED config — any form edit or plain save clears it (the composable
// owns the dirty-watching; see useInboxTestResult).
const { testCount, testError, clear: clearTestResult, setCount, setError, mutateSilently } =
  useInboxTestResult(form)

const saveAndTestDisabled = computed(() =>
  isSaveAndTestDisabled({ saving: saving.value, testing: testing.value, enabled: form.enabled }),
)

// Plain "Save" (no test) — always available so a disabled inbox can still be
// persisted. A previous test result no longer describes the newly-saved config,
// so it is cleared even if the form watcher already did.
async function onSave() {
  clearTestResult()
  try {
    await saveAsync()
    await mutateSilently(() => { form.password = '' })
    toast.add({ severity: 'success', summary: t('ui.inbox.saved'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.inbox.failed_save'), life: 3000 })
  }
}

// test_inbox tests the SAVED config, so we SAVE first, then TEST (save-then-test).
// A save failure means the test never ran and is labeled as a SAVE error, not a
// failed connection test (SaveThenTestError.step distinguishes the two).
async function onSaveAndTest() {
  clearTestResult()
  testing.value = true
  try {
    const result = await runSaveThenTest(() => saveAsync(), () => testInbox())
    await mutateSilently(() => { form.password = '' })
    setCount(result.count)
    toast.add({ severity: 'success', summary: t('ui.inbox.saved'), life: 2000 })
  } catch (e) {
    const step = e instanceof SaveThenTestError ? e.step : 'test'
    setError(step)
    toast.add({
      severity: 'error',
      summary: step === 'save' ? t('ui.inbox.failed_save') : t('ui.inbox.test_failed'),
      life: 3000,
    })
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div>
    <h2>{{ t('ui.inbox.title') }}</h2>
    <p class="section-hint">{{ t('ui.inbox.description') }}</p>

    <Card class="mb-4" style="max-width: 560px"><template #content>
      <div class="enable-toggle">
        <ToggleSwitch v-model="form.enabled" inputId="inbox-enabled" :disabled="isLoading" />
        <span>{{ form.enabled ? t('ui.inbox.enabled') : t('ui.inbox.disabled') }}</span>
      </div>

      <template v-if="form.enabled">
        <div class="form-field">
          <label for="inbox-hostname">{{ t('ui.inbox.hostname') }}</label>
          <InputText id="inbox-hostname" v-model="form.hostname" class="w-full" />
        </div>

        <div class="form-field">
          <label for="inbox-port">{{ t('ui.inbox.port') }}</label>
          <InputNumber inputId="inbox-port" v-model="form.port" :useGrouping="false" class="w-full" :min="0" />
        </div>

        <div class="form-field">
          <div class="inline-toggle">
            <ToggleSwitch v-model="form.starttls" inputId="inbox-starttls" />
            <span>{{ t('ui.inbox.starttls') }}</span>
          </div>
        </div>

        <div class="form-field">
          <label for="inbox-username">{{ t('ui.inbox.username') }}</label>
          <InputText id="inbox-username" v-model="form.username" :autocomplete="'off'" class="w-full" />
        </div>

        <div class="form-field">
          <label for="inbox-password">{{ t('ui.inbox.password') }}</label>
          <Password inputId="inbox-password" v-model="form.password" :feedback="false" toggleMask :inputProps="{ autocomplete: 'new-password', name: 'inbox-password' }" :placeholder="t('ui.inbox.password_keep')" inputClass="w-full" class="w-full" />
          <small class="field-hint">{{ t('ui.inbox.password_keep') }}</small>
        </div>

        <div class="form-field">
          <label for="inbox-folder">{{ t('ui.inbox.folder') }}</label>
          <InputText id="inbox-folder" v-model="form.folder" class="w-full" />
          <small class="field-hint">{{ t('ui.inbox.folder_hint') }}</small>
        </div>

        <div class="form-field">
          <label for="inbox-tag">{{ t('ui.inbox.tag') }}</label>
          <InputText id="inbox-tag" v-model="form.tag" class="w-full" />
          <small class="field-hint">{{ t('ui.inbox.tag_hint') }}</small>
        </div>

        <div class="form-field">
          <div class="inline-toggle">
            <ToggleSwitch v-model="form.autoTagsEnabled" inputId="inbox-auto-tags" />
            <span>{{ t('ui.inbox.auto_tags') }}</span>
          </div>
          <small class="field-hint">{{ t('ui.inbox.auto_tags_hint') }}</small>
        </div>

        <div class="form-field">
          <div class="inline-toggle">
            <ToggleSwitch v-model="form.deleteImported" inputId="inbox-delete-imported" />
            <span>{{ t('ui.inbox.delete_imported') }}</span>
          </div>
          <small class="field-hint">{{ t('ui.inbox.delete_imported_hint') }}</small>
        </div>
      </template>

      <div class="actions">
        <Button :label="t('save')" icon="pi pi-check" :loading="saving && !testing" @click="onSave" />
        <Button
          :label="t('ui.inbox.save_and_test')"
          icon="pi pi-play"
          severity="secondary"
          outlined
          :loading="testing"
          :disabled="saveAndTestDisabled"
          @click="onSaveAndTest"
        />
      </div>

      <Message v-if="testCount !== null" severity="success" class="test-result" :closable="false">
        {{ t('ui.inbox.test_success', { count: testCount }) }}
      </Message>
      <Message v-else-if="testError !== null" severity="error" class="test-result" :closable="false">
        {{ testError === 'save' ? t('ui.inbox.failed_save') : t('ui.inbox.test_failed') }}
      </Message>
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
.inline-toggle {
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
.w-full {
  width: 100%;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 0.5rem;
}
.test-result {
  margin-top: 1rem;
}
</style>
