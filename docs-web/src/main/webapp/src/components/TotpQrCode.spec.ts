import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TotpQrCode from './TotpQrCode.vue'

describe('TotpQrCode', () => {
  it('renders a self-contained QR image encoding the exact otpauth URI', () => {
    const wrapper = mount(TotpQrCode, {
      props: { secret: 'JBSWY3DPEHPK3PXP', issuer: 'Teedy', account: 'alice', alt: 'setup qr' },
    })
    const img = wrapper.get('img.totp-qr')

    // The otpauth URI (with the SHA1/6/30 parameters) is exposed for scanning/testing.
    expect(img.attributes('data-otpauth-uri')).toBe(
      'otpauth://totp/Teedy:alice?secret=JBSWY3DPEHPK3PXP&issuer=Teedy&algorithm=SHA1&digits=6&period=30',
    )
    // The image is a self-hosted data: URL (no external request leaves the page).
    expect(img.attributes('src')).toMatch(/^data:image\//)
    expect(img.attributes('alt')).toBe('setup qr')
  })

  it('recomputes the URI when the secret changes', async () => {
    const wrapper = mount(TotpQrCode, {
      props: { secret: 'AAAA', issuer: 'Teedy', account: 'bob' },
    })
    await wrapper.setProps({ secret: 'BBBB' })
    expect(wrapper.get('img.totp-qr').attributes('data-otpauth-uri')).toContain('secret=BBBB')
  })
})
