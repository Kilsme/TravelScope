import { expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

function luminance(hex: string) {
  const raw = hex.slice(1)
  const rgb = raw.length === 3 ? raw.split('').map(c => c + c).join('') : raw
  const channels = [0, 2, 4].map(offset => parseInt(rgb.slice(offset, offset + 2), 16) / 255)
    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
  return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
}

it('keeps normal text readable on every preset including pastel actions and timestamps', () => {
  const css = readFileSync(new URL('../src/preferences/styles.css', import.meta.url), 'utf8')
  let base: Record<string, string> = {}
  const pairs = [['text','page-bg'],['muted','page-bg'],['sidebar-text','sidebar-bg'],
    ['sidebar-muted','sidebar-bg'],['on-accent','accent-solid'],['on-danger','danger-solid'],['danger-text','surface-alt']]
  const blocks = [...css.matchAll(/:root(?:\[data-theme='([^']+)'\])?\s*\{([^}]+)\}/g)]
  expect(blocks).toHaveLength(4)
  for (const block of blocks) {
    const colors = { ...base, ...Object.fromEntries([...block[2].matchAll(/--([\w-]+):\s*(#[\da-f]{3,8})/gi)].map(match => [match[1], match[2]])) }
    if (!block[1]) base = colors
    for (const [foreground, background] of pairs) {
      const values = [luminance(colors[foreground]), luminance(colors[background])]
      expect((Math.max(...values) + .05) / (Math.min(...values) + .05), `${block[1] ?? 'light'}: ${foreground} / ${background}`).toBeGreaterThanOrEqual(4.5)
    }
  }
})
