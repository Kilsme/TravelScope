import { describe, expect, it } from 'vitest'
import { galleryIndex } from './gallery'

describe('circular image navigation', () => {
  it('moves in both directions and wraps at both ends', () => {
    expect(galleryIndex(0,1,3)).toBe(1)
    expect(galleryIndex(1,-1,3)).toBe(0)
    expect(galleryIndex(2,1,3)).toBe(0)
    expect(galleryIndex(0,-1,3)).toBe(2)
  })
  it('keeps single and empty galleries safe', () => {
    expect(galleryIndex(0,1,1)).toBe(0)
    expect(galleryIndex(0,-1,1)).toBe(0)
    expect(galleryIndex(0,1,0)).toBe(0)
  })
})
