import { describe, it, expect, beforeEach, vi } from 'vitest'

// Only the RUNTIME palette application is mocked — `palette` and `definePreset` stay real, since
// the point of these tests is that PrimeVue's own derivation is what produces the scale.
const updatePrimaryPaletteMock = vi.hoisted(() => vi.fn())
vi.mock('@primeuix/themes', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@primeuix/themes')>()
  return { ...actual, updatePrimaryPalette: updatePrimaryPaletteMock }
})

import {
  applyBrandPrimary,
  buildPreset,
  derivePrimaryScale,
  getBrandPrimary,
  resetBrandPrimaryForTest,
  setBrandPrimary,
  teedyPrimary,
} from './primary'
import type { ThemePreset } from './presets'

// A stand-in for a family preset (Aura/Lara/Material/Nora). Only the semantic slice matters here;
// loading a real one pulls the whole ~450 kB token set into the test.
function fakeFamily(name: string): ThemePreset {
  return { family: name, semantic: { primary: { 500: '#000000' } } } as unknown as ThemePreset
}

beforeEach(() => {
  resetBrandPrimaryForTest()
  updatePrimaryPaletteMock.mockClear()
})

describe('derivePrimaryScale', () => {
  it('derives a full 50…950 scale from a hex brand colour, keyed on the colour itself', () => {
    const scale = derivePrimaryScale('#ff5722')!
    expect(scale).not.toBeNull()
    expect(scale['500']).toBe('#ff5722')
    for (const step of ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950']) {
      expect(scale[step], `step ${step}`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('rejects anything that is not a hex colour, so a half-derived palette never renders', () => {
    expect(derivePrimaryScale('purple')).toBeNull()
    expect(derivePrimaryScale('#12345')).toBeNull()
    expect(derivePrimaryScale('')).toBeNull()
    expect(derivePrimaryScale(null)).toBeNull()
    expect(derivePrimaryScale(undefined)).toBeNull()
  })
})

describe('buildPreset — the single preset builder', () => {
  it('embeds the STOCK brand primary before any custom colour is applied', () => {
    const preset = buildPreset(fakeFamily('Lara')) as { semantic: { primary: Record<string, string> } }
    expect(preset.semantic.primary).toMatchObject(teedyPrimary)
  })

  it('embeds the CUSTOM brand primary once one is set — for every family', () => {
    const branded = derivePrimaryScale('#ff5722')!
    setBrandPrimary(branded)

    // This is the #241 regression: a family switch rebuilds the preset from that family's base,
    // and anything the rebuild does not carry over is silently lost.
    for (const family of ['Aura', 'Lara', 'Material', 'Nora']) {
      const preset = buildPreset(fakeFamily(family)) as { semantic: { primary: Record<string, string> } }
      expect(preset.semantic.primary, `family ${family}`).toMatchObject(branded)
    }
  })

  it('falls back to the stock primary when the brand primary is cleared', () => {
    setBrandPrimary(derivePrimaryScale('#ff5722'))
    setBrandPrimary(null)
    const preset = buildPreset(fakeFamily('Nora')) as { semantic: { primary: Record<string, string> } }
    expect(preset.semantic.primary).toMatchObject(teedyPrimary)
  })
})

describe('applyBrandPrimary', () => {
  it('derives the configured colour and pushes it to the running theme', () => {
    applyBrandPrimary('#ff5722')
    expect(getBrandPrimary()['500']).toBe('#ff5722')
    expect(updatePrimaryPaletteMock).toHaveBeenCalledTimes(1)
    expect(updatePrimaryPaletteMock.mock.calls[0][0]).toMatchObject(derivePrimaryScale('#ff5722')!)
  })

  it('is a no-op for an instance with no brand colour — no redundant palette update at boot', () => {
    applyBrandPrimary(null)
    applyBrandPrimary('')
    applyBrandPrimary(undefined)
    expect(updatePrimaryPaletteMock).not.toHaveBeenCalled()
    expect(getBrandPrimary()).toBe(teedyPrimary)
  })

  it('does not re-apply an unchanged colour (the shared theme query emits repeatedly)', () => {
    applyBrandPrimary('#336699')
    applyBrandPrimary('#336699')
    applyBrandPrimary('#336699')
    expect(updatePrimaryPaletteMock).toHaveBeenCalledTimes(1)
  })

  it('restores the stock primary when an admin clears the brand colour', () => {
    applyBrandPrimary('#336699')
    applyBrandPrimary('')
    expect(getBrandPrimary()).toBe(teedyPrimary)
    expect(updatePrimaryPaletteMock).toHaveBeenCalledTimes(2)
    expect(updatePrimaryPaletteMock.mock.calls[1][0]).toBe(teedyPrimary)
  })

  it('ignores an unusable colour rather than rendering a half-derived palette', () => {
    applyBrandPrimary('not-a-color')
    expect(getBrandPrimary()).toBe(teedyPrimary)
  })
})
