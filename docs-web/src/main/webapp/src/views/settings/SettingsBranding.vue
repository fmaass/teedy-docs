<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMutation, useQuery } from '@tanstack/vue-query'
import {
  getTheme,
  getThemeScript,
  saveTheme,
  saveThemeStylesheet,
  deleteThemeStylesheet,
  saveThemeScript,
  deleteThemeScript,
  uploadThemeImage,
  deleteThemeImage,
  type ThemeImageType,
} from '../../api/theme'
import { queryKeys } from '../../api/queryKeys'
import { useInvalidateTheme } from '../../composables/useThemeBranding'
import { teedyPrimary } from '../../theme/primary'
import { useConfirmDanger } from '../../composables/useConfirmDanger'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import ColorPicker from 'primevue/colorpicker'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'

// #57 + #241: the Branding admin screen. The theme API (name, navbar colour, custom CSS,
// logo/background/favicon) has existed since v1.5 but NOTHING in the SPA ever called a theme
// mutation — branding was an API-only capability. This screen is the missing half, and it also
// carries the two #241 additions: a brand colour the whole UI palette derives from, and a custom
// script.
//
// Field semantics follow the endpoint: POST /theme preserves fields it is not given and clears
// the ones it is given empty, so this form always submits its three identity fields together and
// an empty box genuinely means "reset this".

const { t } = useI18n()
const toast = useToast()
const { confirmDanger } = useConfirmDanger()
const invalidateTheme = useInvalidateTheme()

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/
const IMAGE_TYPES: ThemeImageType[] = ['logo', 'background', 'favicon']
// Shown while the field is empty, which means "use the stock value" rather than "unconfigured".
const NAVBAR_COLOR_SAMPLE = '#ffffff'
// The brand colour the app ACTUALLY falls back to, read from the palette module rather than
// re-typed so the placeholder and the swatch beside it can never drift from it.
//
// It is also handed to the ColorPicker as its `defaultColor`, because PrimeVue substitutes its own
// — 'ff0000' — whenever the bound value is empty, and paints it onto the preview as an inline
// background-color that no stylesheet can override. That made a fresh install look like it had
// deliberately picked RED as its brand while the text field correctly showed the stock colour.
// Display only: the v-model stays empty, so "empty means reset" is unchanged.
const BRAND_COLOR_SAMPLE = teedyPrimary['500']

const { data: theme } = useQuery({
  queryKey: queryKeys.theme(),
  queryFn: () => getTheme(),
  staleTime: Infinity,
})

const { data: scriptSource, refetch: refetchScript } = useQuery({
  queryKey: queryKeys.themeScript(),
  queryFn: () => getThemeScript(),
  staleTime: Infinity,
})

const appName = ref('')
const navbarColor = ref('')
const mainColor = ref('')
const customCss = ref('')
const customJs = ref('')

// Seeded ONCE. Every save invalidates the shared theme query so the live branding follows, and
// that refetch re-fires this watcher — reseeding on each emission would wipe whatever the admin
// has typed since.
let seeded = false
watch(theme, (config) => {
  if (!config || seeded) return
  appName.value = config.name ?? ''
  navbarColor.value = config.color ?? ''
  mainColor.value = config.main_color ?? ''
  customCss.value = config.css ?? ''
  seeded = true
}, { immediate: true })

let scriptSeeded = false
watch(scriptSource, (source) => {
  if (source === undefined || scriptSeeded) return
  customJs.value = source
  scriptSeeded = true
}, { immediate: true })

/**
 * PrimeVue's ColorPicker speaks bare hex ("336699"); the app and the API speak "#336699". These
 * proxies keep the text field authoritative — it is the one an admin can clear.
 */
function swatch(model: typeof navbarColor) {
  return computed<string>({
    get: () => (HEX_COLOR.test(model.value) ? model.value.slice(1) : ''),
    set: (value: string) => {
      model.value = value ? `#${value}` : ''
    },
  })
}
const navbarSwatch = swatch(navbarColor)
const mainSwatch = swatch(mainColor)

const identityError = computed(() => {
  const name = appName.value.trim()
  if (name && (name.length < 3 || name.length > 30)) return t('ui.branding.name_invalid')
  for (const value of [navbarColor.value.trim(), mainColor.value.trim()]) {
    if (value && !HEX_COLOR.test(value)) return t('ui.branding.color_invalid')
  }
  return ''
})

const { mutate: saveIdentity, isPending: savingIdentity } = useMutation({
  // All three go over the wire on every save, including as empty strings: an empty box is a
  // deliberate reset, and the endpoint's absent=preserve rule protects everything NOT listed here
  // (the custom CSS above all).
  mutationFn: () => saveTheme({
    name: appName.value.trim(),
    color: navbarColor.value.trim(),
    main_color: mainColor.value.trim(),
  }),
  onSuccess: async () => {
    await invalidateTheme()
    toast.add({ severity: 'success', summary: t('ui.branding.saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.branding.failed_save'), life: 3000 })
  },
})

function submitIdentity() {
  if (identityError.value) {
    toast.add({ severity: 'error', summary: identityError.value, life: 3000 })
    return
  }
  saveIdentity()
}

const { mutate: saveCss, isPending: savingCss } = useMutation({
  mutationFn: () => (customCss.value.trim() ? saveThemeStylesheet(customCss.value) : deleteThemeStylesheet()),
  onSuccess: async () => {
    // The refetch this triggers carries the new stylesheet_version, which is what makes the
    // injected <link> re-fetch the compiled stylesheet.
    await invalidateTheme()
    toast.add({ severity: 'success', summary: t('ui.branding.css_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.branding.css_failed'), life: 3000 })
  },
})

const { mutate: saveJs, isPending: savingJs } = useMutation({
  mutationFn: () => (customJs.value.trim() ? saveThemeScript(customJs.value) : deleteThemeScript()),
  onSuccess: async () => {
    await invalidateTheme()
    await refetchScript()
    toast.add({ severity: 'success', summary: t('ui.branding.js_saved'), life: 2000 })
  },
  onError: () => {
    toast.add({ severity: 'error', summary: t('ui.branding.js_failed'), life: 3000 })
  },
})

function submitJs() {
  // Saving a non-empty script hands every signed-in user's session to that code. It is a
  // legitimate operator capability, not a privilege boundary — the confirmation exists so nobody
  // arrives there by autopilot. Clearing the script needs no confirmation.
  if (!customJs.value.trim()) {
    saveJs()
    return
  }
  confirmDanger({
    header: t('ui.branding.js_confirm_header'),
    message: t('ui.branding.js_confirm'),
    icon: 'pi pi-exclamation-triangle',
    accept: () => saveJs(),
  })
}

// --- Images ---
// Each preview URL carries the image's own version token, because the image responses are cached
// for 15 days: without it a replaced logo would keep showing its predecessor here.
function imageVersion(type: ThemeImageType): number {
  if (type === 'logo') return theme.value?.logo_version ?? 0
  if (type === 'background') return theme.value?.background_version ?? 0
  return theme.value?.favicon_version ?? 0
}

function imageUrl(type: ThemeImageType): string {
  return `/api/theme/image/${type}?v=${imageVersion(type)}`
}

function imageLabel(type: ThemeImageType): string {
  if (type === 'logo') return t('ui.branding.image_logo')
  if (type === 'background') return t('ui.branding.image_background')
  return t('ui.branding.image_favicon')
}

// Per-image guidance on the recommended file format and pixel size (#273). Each image is
// rendered differently — the logo is scaled down to fit the header, the background covers the
// login screen, the favicon is a fixed small square — so the advice is per-type, not shared.
function imageHint(type: ThemeImageType): string {
  if (type === 'logo') return t('ui.branding.image_logo_hint')
  if (type === 'background') return t('ui.branding.image_background_hint')
  return t('ui.branding.image_favicon_hint')
}

const imageInput = ref<HTMLInputElement | null>(null)
const pendingImageType = ref<ThemeImageType | null>(null)
const busyImageType = ref<ThemeImageType | null>(null)

function pickImage(type: ThemeImageType) {
  pendingImageType.value = type
  imageInput.value?.click()
}

async function onImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const type = pendingImageType.value
  input.value = ''
  pendingImageType.value = null
  if (!file || !type) return

  busyImageType.value = type
  try {
    await uploadThemeImage(type, file)
    await invalidateTheme()
    toast.add({ severity: 'success', summary: t('ui.branding.image_saved'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.branding.image_failed'), life: 3000 })
  } finally {
    busyImageType.value = null
  }
}

async function resetImage(type: ThemeImageType) {
  busyImageType.value = type
  try {
    await deleteThemeImage(type)
    await invalidateTheme()
    toast.add({ severity: 'success', summary: t('ui.branding.image_reset'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('ui.branding.image_failed'), life: 3000 })
  } finally {
    busyImageType.value = null
  }
}
</script>

<template>
  <div class="branding-settings">
    <h2>{{ t('ui.branding.title') }}</h2>
    <p class="section-hint">{{ t('ui.branding.intro') }}</p>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.branding.identity') }}</h3>
      <div class="form-field">
        <label for="branding-app-name">{{ t('ui.branding.app_name') }}</label>
        <InputText id="branding-app-name" v-model="appName" class="w-full" :maxlength="30" />
        <small class="field-hint">{{ t('ui.branding.app_name_hint') }}</small>
      </div>

      <div class="form-field">
        <label for="branding-navbar-color">{{ t('ui.branding.navbar_color') }}</label>
        <div class="color-row">
          <ColorPicker v-model="navbarSwatch" format="hex" :aria-label="t('ui.branding.navbar_color')" />
          <InputText id="branding-navbar-color" v-model="navbarColor" class="color-hex" :placeholder="NAVBAR_COLOR_SAMPLE" />
          <Button
            :label="t('ui.branding.clear')"
            severity="secondary"
            text
            @click="navbarColor = ''"
          />
        </div>
      </div>

      <div class="form-field">
        <label for="branding-main-color">{{ t('ui.branding.brand_color') }}</label>
        <div class="color-row">
          <ColorPicker
            v-model="mainSwatch"
            format="hex"
            :default-color="BRAND_COLOR_SAMPLE"
            :aria-label="t('ui.branding.brand_color')"
          />
          <InputText id="branding-main-color" v-model="mainColor" class="color-hex" :placeholder="BRAND_COLOR_SAMPLE" />
          <Button
            :label="t('ui.branding.clear')"
            severity="secondary"
            text
            @click="mainColor = ''"
          />
        </div>
        <small class="field-hint">{{ t('ui.branding.brand_color_hint') }}</small>
      </div>

      <Message v-if="identityError" severity="error" :closable="false" class="mb-3">{{ identityError }}</Message>
      <Button :label="t('save')" icon="pi pi-check" :loading="savingIdentity" @click="submitIdentity" />
    </template></Card>

    <Card class="mb-4" style="max-width: 520px"><template #content>
      <h3>{{ t('ui.branding.images') }}</h3>
      <p class="section-hint">{{ t('ui.branding.images_hint') }}</p>
      <div v-for="type in IMAGE_TYPES" :key="type" class="image-row">
        <img :src="imageUrl(type)" :alt="imageLabel(type)" class="image-preview" />
        <span class="image-label">{{ imageLabel(type) }}</span>
        <Button
          :label="t('ui.branding.upload')"
          icon="pi pi-upload"
          severity="secondary"
          outlined
          :loading="busyImageType === type"
          :aria-label="`${t('ui.branding.upload')} ${imageLabel(type)}`"
          @click="pickImage(type)"
        />
        <Button
          :label="t('ui.branding.reset')"
          icon="pi pi-undo"
          severity="secondary"
          text
          :loading="busyImageType === type"
          :aria-label="`${t('ui.branding.reset')} ${imageLabel(type)}`"
          @click="resetImage(type)"
        />
        <small class="field-hint image-hint">{{ imageHint(type) }}</small>
      </div>
      <input ref="imageInput" type="file" accept="image/*" hidden @change="onImagePicked" />
    </template></Card>

    <Card class="mb-4" style="max-width: 720px"><template #content>
      <h3>{{ t('ui.branding.css_title') }}</h3>
      <p class="section-hint">{{ t('ui.branding.css_hint') }}</p>
      <Textarea id="branding-css" v-model="customCss" rows="10" class="w-full code-area" spellcheck="false" />
      <Button :label="t('save')" icon="pi pi-check" :loading="savingCss" class="mt-3" @click="saveCss()" />
    </template></Card>

    <Card class="mb-4 danger-zone" style="max-width: 720px"><template #content>
      <h3 class="danger-zone-title">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />{{ t('ui.branding.js_title') }}
      </h3>
      <Message severity="warn" :closable="false" class="mb-3">{{ t('ui.branding.js_warning') }}</Message>
      <p class="section-hint">{{ t('ui.branding.js_hint') }}</p>
      <Textarea id="branding-js" v-model="customJs" rows="10" class="w-full code-area" spellcheck="false" />
      <Button :label="t('save')" icon="pi pi-check" severity="danger" :loading="savingJs" class="mt-3" @click="submitJs" />
    </template></Card>
  </div>
</template>

<style scoped>
h3 { margin: 0 0 1rem; font-size: 1.125rem; }
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
.section-hint {
  margin: 0 0 1rem;
  font-size: 0.8125rem;
  color: var(--p-text-muted-color);
  line-height: 1.5;
}
.field-hint {
  display: block;
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--p-text-muted-color);
}
.color-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.color-hex {
  flex: 1 1 auto;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.image-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.image-preview {
  width: 48px;
  height: 48px;
  object-fit: contain;
  border: 1px solid var(--p-content-border-color);
  border-radius: 4px;
  background: var(--p-content-background);
}
/* #273: the label heads its own full-width line above the preview and buttons, rather than
   competing with them for one row. The long German "Anmeldehintergrund" used to shrink against
   the Upload button (it had min-width:0 and could fall below its content width); as a heading it
   always has room, in every locale, and the layout stays consistent whatever the string length. */
.image-label {
  order: -1;
  flex: 0 0 100%;
  font-size: 0.875rem;
  font-weight: 500;
}
/* The format/size guidance sits on its own line below the controls. */
.image-hint {
  flex: 0 0 100%;
}
.code-area {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.8125rem;
}
/* The custom-script block is fenced like the Config maintenance zone: admin-injected JavaScript
   runs with every signed-in user's session, so it should read as consequential before it reads
   as convenient. */
.danger-zone {
  border: 1px solid var(--p-red-400, #f87171);
}
.danger-zone-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--p-red-500, #ef4444);
}
.mb-3 {
  margin-bottom: 0.75rem;
}
.mt-3 {
  margin-top: 0.75rem;
}
.w-full {
  width: 100%;
}
</style>
