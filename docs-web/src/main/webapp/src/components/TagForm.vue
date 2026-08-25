<script setup lang="ts">
/**
 * The tag form — name, colour, parent, and the permissions section — as ONE implementation
 * with TWO hosts (#288).
 *
 * It was extracted verbatim from TagEdit.vue, which owned the only copy: the reporter asked
 * for "the same editor which is already present" inside the document edit view, and a second
 * form would have meant every later tag-form change landing on one surface only. The hosts are
 * now:
 *   - views/tag/TagEdit.vue        — editing an existing tag on the tag management page
 *   - components/TagCreatePanel.vue — creating one from the document editor's side panel
 *
 * Contract notes:
 * - The host owns the VALUES (`v-model:name` / `:color` / `:parent`) and the ACL state. This
 *   component renders and reports; it fetches and saves nothing, because the two hosts save
 *   through completely different calls (POST /tag/{id} vs PUT /tag + deferred grants).
 * - `color` is the hex WITHOUT the leading '#', which is what PrimeVue's ColorPicker binds and
 *   what both hosts have always stored.
 * - `idPrefix` prefixes every field id. `tag-name`/`tag-color-label`/`tag-parent` are e2e
 *   selectors on the management page (e2e/tags.spec.ts), so that host keeps `tag`; the panel
 *   takes its own prefix, which is also what stops the two forms colliding on ids should they
 *   ever share a page.
 * - `parentOptions` is the host's to build: the management page must exclude the tag itself
 *   and its descendants (a cycle), while a tag that does not exist yet has neither.
 */
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ColorPicker from 'primevue/colorpicker'
import Card from 'primevue/card'
import AclEditor from './AclEditor.vue'
import type { AclEntry, AclTarget } from '../api/acl'

export interface TagFormParentOption {
  label: string
  value: string | null
}

/** Everything the permissions section needs, as one object rather than six forwarded props. */
export interface TagFormAcl {
  /** The ACL source id. Empty (and unread) while `deferred` is set — see AclEditor. */
  sourceId: string
  entries: AclEntry[]
  writable: boolean
  immutable?: (acl: AclEntry) => boolean | string
  beforeAdd?: (perm: 'READ' | 'WRITE', target: AclTarget) => boolean | Promise<boolean>
  /** The tag does not exist yet: collect grants instead of sending them. */
  deferred?: boolean
}

const props = defineProps<{
  name: string
  /** Hex colour WITHOUT the leading '#'. */
  color: string
  parent: string | null
  parentOptions: TagFormParentOption[]
  idPrefix: string
  acl: TagFormAcl
  /**
   * Drop the card chrome. The management page has always shown the form as two cards and its
   * screenshots pin that; the side panel is already a surface of its own, so it renders flat.
   */
  flat?: boolean
  /** Max width of each card. The management page has always constrained the form to 480px. */
  maxWidth?: string
  /**
   * Put the caret in the Name field when the form appears. Only the side panel wants this —
   * the panel opens BECAUSE the user is naming a tag. The management page must not grab focus
   * on load, so it leaves this off. PrimeVue's Drawer focuses `[autofocus]` inside its content
   * ahead of anything else, which is the whole of the panel's focus handling.
   */
  autofocusName?: boolean
}>()

const emit = defineEmits<{
  'update:name': [value: string]
  'update:color': [value: string]
  'update:parent': [value: string | null]
  /** The persisted ACL list changed on the server; the host must re-read it. */
  'acl-changed': []
  /** Deferred mode: a grant to hold until the tag exists. */
  'acl-add': [acl: AclEntry]
  /** Deferred mode: a held grant to drop again. */
  'acl-remove': [acl: AclEntry]
}>()

const { t } = useI18n()
</script>

<template>
  <slot name="lead" />

  <Card class="tag-form-card" :class="{ flat }" :style="maxWidth ? { maxWidth } : undefined">
    <template #content>
      <div class="form-field">
        <label :for="`${idPrefix}-name`">{{ t('ui.tag_edit.name') }}</label>
        <InputText
          :id="`${idPrefix}-name`"
          :modelValue="props.name"
          :autofocus="autofocusName"
          class="w-full"
          @update:modelValue="emit('update:name', $event ?? '')"
        />
      </div>
      <div class="form-field">
        <label :id="`${idPrefix}-color-label`">{{ t('ui.tag_edit.color') }}</label>
        <div class="color-row">
          <ColorPicker
            :modelValue="props.color"
            :aria-labelledby="`${idPrefix}-color-label`"
            @update:modelValue="emit('update:color', String($event ?? ''))"
          />
          <span class="color-preview" :style="{ background: '#' + props.color }">{{
            props.name || t('ui.tag_edit.preview')
          }}</span>
        </div>
      </div>
      <div class="form-field">
        <label :for="`${idPrefix}-parent`">{{ t('ui.tag_edit.parent') }}</label>
        <Select
          :modelValue="props.parent"
          :inputId="`${idPrefix}-parent`"
          :options="parentOptions"
          optionLabel="label"
          optionValue="value"
          class="w-full"
          showClear
          filter
          :placeholder="t('ui.tag_edit.no_parent')"
          @update:modelValue="emit('update:parent', $event ?? null)"
        />
      </div>
      <slot name="actions" />
    </template>
  </Card>

  <Card
    class="tag-form-card acl-card"
    :class="{ flat }"
    :style="maxWidth ? { maxWidth } : undefined"
  >
    <template #content>
      <h2 class="acl-heading">{{ t('ui.tag_acl.title') }}</h2>
      <p class="acl-desc">{{ t('ui.tag_acl.description') }}</p>
      <slot name="permissions-hint" />
      <AclEditor
        :source-id="acl.sourceId"
        :acls="acl.entries"
        :writable="acl.writable"
        :immutable="acl.immutable"
        :before-add="acl.beforeAdd"
        :deferred="acl.deferred"
        @changed="emit('acl-changed')"
        @add="emit('acl-add', $event)"
        @remove="emit('acl-remove', $event)"
      />
    </template>
  </Card>
</template>

<style scoped>
/*
 * Moved here from TagEdit.vue together with the markup they style. A scoped rule only reaches
 * the template it is written in, so leaving them behind would have silently unstyled the form
 * the moment it moved out of that file.
 */
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

.color-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.color-preview {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.75rem;
  border-radius: 4px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--teedy-tag-text);
}

.acl-card {
  margin-top: 1.25rem;
}
.acl-heading {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}
.acl-desc {
  margin: 0.25rem 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
}

/*
 * The flat (side-panel) variant. The drawer the panel lives in is already a raised surface, so
 * a card inside it would be a second one; the same markup renders as plain sections instead.
 */
.tag-form-card.flat {
  background: none;
  box-shadow: none;
  border-radius: 0;
}
.tag-form-card.flat :deep(.p-card-body) {
  padding: 0;
  gap: 0;
}
.tag-form-card.flat.acl-card {
  margin-top: 1.5rem;
}
</style>
