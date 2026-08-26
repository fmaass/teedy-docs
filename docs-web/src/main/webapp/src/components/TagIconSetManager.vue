<script setup lang="ts">
/**
 * The instance's custom tag-icon set (#287), maintained where tags are maintained.
 *
 * It lives on the TAG MANAGEMENT page rather than behind a new Settings entry, folded away until
 * asked for. Two reasons: the set exists only to be put on tags, so this is where somebody is
 * standing when they need it; and the settings hub is a captured visual surface whose card grid a
 * new entry would rearrange.
 *
 * Uploading and deleting are ADMIN acts — an upload writes a file the whole instance then loads —
 * and the server enforces that independently. Everyone else sees the set read-only, which is
 * worth showing: it is the same list their tag form offers, and knowing what is in it is how you
 * know what to ask an administrator for.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { deleteTagIcon, listTagIcons, uploadTagIcon } from '../api/tag'
import { queryKeys } from '../api/queryKeys'
import { tagIconDataUrl } from '../utils/tagIcon'
import { useAuthStore } from '../stores/auth'
import { useConfirmDanger } from '../composables/useConfirmDanger'

const { t } = useI18n()
const toast = useToast()
const auth = useAuthStore()
const queryClient = useQueryClient()
const { confirmDanger } = useConfirmDanger()

const expanded = ref(false)
const newIconName = ref('')
const uploading = ref(false)
const uploadError = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const { data: icons, isLoading } = useQuery({
  queryKey: queryKeys.tagIcons(),
  queryFn: () => listTagIcons().then((r) => r.data.icons),
  staleTime: 60_000,
})

const canManage = computed(() => auth.isAdmin)
const canUpload = computed(() => canManage.value && !!newIconName.value.trim() && !uploading.value)

function pickFile() {
  uploadError.value = null
  fileInput.value?.click()
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const name = newIconName.value.trim()
  // Cleared straight away so picking the SAME file twice still fires a change event.
  input.value = ''
  if (!file || !name) return

  uploading.value = true
  uploadError.value = null
  try {
    await uploadTagIcon(name, file)
    // Every icon picker on the page reads this key, so they all pick the new icon up at once.
    await queryClient.invalidateQueries({ queryKey: queryKeys.tagIcons() })
    newIconName.value = ''
    toast.add({ severity: 'success', summary: t('ui.tag_icon.uploaded'), life: 2000 })
  } catch (error) {
    // The server's own reason — too large, not a PNG or SVG, an SVG carrying a script. Shown
    // beside the field rather than in a toast: it is a correction to make, not a notification.
    uploadError.value =
      (error as { response?: { data?: { message?: string } } }).response?.data?.message ||
      t('ui.tag_icon.upload_failed')
  } finally {
    uploading.value = false
  }
}

function removeIcon(id: string, name: string) {
  confirmDanger({
    header: t('ui.tag_icon.delete_header'),
    // The count is not known before the fact, so the warning states the RULE rather than a
    // number: tags using this icon keep working, they simply stop having one.
    message: t('ui.tag_icon.delete_confirm', { name }),
    accept: async () => {
      try {
        const { data } = await deleteTagIcon(id)
        await queryClient.invalidateQueries({ queryKey: queryKeys.tagIcons() })
        // The tag list carries each tag's icon, and the server has just cleared this one off
        // however many tags were using it — so that list is now stale everywhere it is drawn.
        await queryClient.invalidateQueries({ queryKey: queryKeys.tags() })
        toast.add({
          severity: 'success',
          summary: t('ui.tag_icon.deleted', { count: data.tags }),
          life: 2500,
        })
      } catch {
        toast.add({ severity: 'error', summary: t('ui.tag_icon.delete_failed'), life: 3000 })
      }
    },
  })
}
</script>

<template>
  <Card class="mb-4 tag-icon-set" style="max-width: 520px">
    <template #content>
      <button
        type="button"
        class="icon-set-header"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        <i :class="expanded ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" aria-hidden="true" />
        <span class="section-title">{{ t('ui.tag_icon.set_title') }}</span>
        <span v-if="icons?.length" class="icon-set-count">{{ icons.length }}</span>
      </button>

      <div v-if="expanded" class="icon-set-body">
        <p class="icon-set-intro">{{ t('ui.tag_icon.set_intro') }}</p>

        <div v-if="isLoading" class="icon-set-hint">{{ t('ui.tag_icon.loading') }}</div>
        <div v-else-if="!icons?.length" class="icon-set-hint">{{ t('ui.tag_icon.set_empty') }}</div>
        <ul v-else class="icon-set-list">
          <li v-for="entry in icons" :key="entry.id" class="icon-set-row">
            <img class="icon-set-preview" :src="tagIconDataUrl(entry.id)" :alt="entry.name" />
            <span class="icon-set-name">{{ entry.name }}</span>
            <Button
              v-if="canManage"
              class="icon-set-delete-btn"
              icon="pi pi-trash"
              text
              rounded
              severity="danger"
              size="small"
              :aria-label="t('ui.tag_icon.delete_icon', { name: entry.name })"
              @click="removeIcon(entry.id, entry.name)"
            />
          </li>
        </ul>

        <template v-if="canManage">
          <div class="icon-set-upload">
            <InputText
              id="tag-icon-name"
              v-model="newIconName"
              class="icon-set-name-input"
              :placeholder="t('ui.tag_icon.name_placeholder')"
              maxlength="50"
            />
            <Button
              class="icon-set-upload-btn"
              :label="t('ui.tag_icon.upload')"
              icon="pi pi-upload"
              size="small"
              :disabled="!canUpload"
              :loading="uploading"
              @click="pickFile"
            />
          </div>
          <small class="icon-set-hint">{{ t('ui.tag_icon.upload_hint') }}</small>
          <Message v-if="uploadError" severity="error" :closable="false" class="mt-2">
            {{ uploadError }}
          </Message>
          <input
            ref="fileInput"
            type="file"
            accept="image/png,image/svg+xml,.png,.svg"
            hidden
            @change="onFilePicked"
          />
        </template>
        <small v-else class="icon-set-hint">{{ t('ui.tag_icon.admin_only') }}</small>
      </div>
    </template>
  </Card>
</template>

<style scoped>
.icon-set-header {
  all: unset;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  cursor: pointer;
}
.icon-set-header:focus-visible {
  outline: 2px solid var(--p-primary-color);
  outline-offset: 2px;
  border-radius: 4px;
}
.section-title {
  font-size: 1rem;
  font-weight: 600;
}
.icon-set-count {
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}

.icon-set-body {
  margin-top: 0.875rem;
}
.icon-set-intro,
.icon-set-hint {
  display: block;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}
.icon-set-intro {
  margin: 0 0 0.75rem;
}

.icon-set-list {
  list-style: none;
  margin: 0 0 0.875rem;
  padding: 0;
  max-height: 14rem;
  overflow-y: auto;
}
.icon-set-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.25rem 0;
}
.icon-set-preview {
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  object-fit: contain;
}
.icon-set-name {
  flex: 1;
  font-size: 0.8125rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icon-set-upload {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-bottom: 0.375rem;
}
.icon-set-name-input {
  flex: 1 1 12rem;
  min-width: 0;
}
</style>
