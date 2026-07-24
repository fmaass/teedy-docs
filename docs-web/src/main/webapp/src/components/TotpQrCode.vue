<script setup lang="ts">
import { computed } from 'vue'
import qrcode from 'qrcode-generator'
import { buildOtpAuthUri } from '../utils/totp'

const props = defineProps<{ secret: string; issuer: string; account: string; alt?: string }>()

// The otpauth:// URI is exposed as a data attribute so the enrollment flow (and its tests) can assert the
// exact URI parameters without decoding the rendered image.
const uri = computed(() =>
  buildOtpAuthUri({ secret: props.secret, issuer: props.issuer, account: props.account }),
)

// qrcode-generator is a zero-dependency, self-contained encoder; type 0 auto-sizes to the data length and
// createDataURL yields a self-hosted data: image (no external request), so nothing leaves the page.
const dataUrl = computed(() => {
  const qr = qrcode(0, 'M')
  qr.addData(uri.value)
  qr.make()
  return qr.createDataURL(6, 8)
})
</script>

<template>
  <img
    class="totp-qr"
    :src="dataUrl"
    :data-otpauth-uri="uri"
    :alt="alt ?? ''"
  />
</template>

<style scoped>
.totp-qr {
  width: 200px;
  height: 200px;
  image-rendering: pixelated;
  background: #ffffff;
  padding: 4px;
  border-radius: 6px;
}
</style>
