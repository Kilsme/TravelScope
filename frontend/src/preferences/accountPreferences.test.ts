import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bindPreferenceOwner, getPreferenceOwner, getPreferences, handlePreferenceStorage,
  parsePreferences, preferencesKey, themes, updatePreferences } from './store'

const values = new Map<string,string>()
beforeEach(() => {
  values.clear()
  vi.stubGlobal('localStorage', {
    getItem: (key:string) => values.get(key) ?? null,
    setItem: (key:string,value:string) => values.set(key,value),
    removeItem: (key:string) => values.delete(key),
  })
  handlePreferenceStorage({ key:null, newValue:null })
})
afterEach(() => { handlePreferenceStorage({ key:null, newValue:null }); vi.unstubAllGlobals() })

describe('account appearance ownership', () => {
  it('accepts every preset and safely rejects unknown themes', () => {
    for (const theme of themes) expect(parsePreferences(JSON.stringify({theme})).theme).toBe(theme)
    expect(parsePreferences('{"theme":"missing"}').theme).toBe('light')
  })
  it('keeps users and admin identities independent and restores them after logout', () => {
    bindPreferenceOwner(1)
    updatePreferences({theme:'peach',locale:'en'})
    bindPreferenceOwner(2)
    expect(getPreferences()).toEqual({theme:'light',locale:'zh-CN'})
    updatePreferences({theme:'blue-bowl'})
    bindPreferenceOwner(null)
    expect(getPreferences()).toEqual({theme:'light',locale:'zh-CN'})
    bindPreferenceOwner(1)
    expect(getPreferences()).toEqual({theme:'peach',locale:'en'})
    bindPreferenceOwner(2)
    expect(getPreferences().theme).toBe('blue-bowl')
    expect(JSON.parse(values.get(preferencesKey(1))!)).toEqual({theme:'peach',locale:'en'})
  })
  it('does not import an unowned legacy preference into a new identity', () => {
    values.set('travelscope:appearance:v1','{"theme":"dark","locale":"en"}')
    bindPreferenceOwner(3)
    expect(getPreferences()).toEqual({theme:'light',locale:'zh-CN'})
    expect(values.has(preferencesKey(3))).toBe(false)
  })
  it('only applies cross-tab preference changes for the active owner', () => {
    bindPreferenceOwner(4)
    handlePreferenceStorage({key:preferencesKey(5),newValue:'{"theme":"peach"}'})
    expect(getPreferences().theme).toBe('light')
    handlePreferenceStorage({key:preferencesKey(4),newValue:'{"theme":"dark","locale":"en"}'})
    expect(getPreferences()).toEqual({theme:'dark',locale:'en'})
    handlePreferenceStorage({key:preferencesKey(4),newValue:null})
    expect(getPreferences()).toEqual({theme:'light',locale:'zh-CN'})
  })
  it('rebinds when another tab signs out or changes account', () => {
    values.set('travelscope_token','test-token')
    values.set('travelscope_user_id','6')
    values.set(preferencesKey(6),'{"theme":"peach"}')
    handlePreferenceStorage({key:'travelscope_user_id',newValue:'6'})
    expect(getPreferenceOwner()).toBe(6)
    expect(getPreferences().theme).toBe('peach')
    values.delete('travelscope_token')
    handlePreferenceStorage({key:'travelscope_token',newValue:null})
    expect(getPreferenceOwner()).toBeNull()
    expect(getPreferences().theme).toBe('light')
  })
  it('retains per-account in-memory preferences when storage fails', () => {
    vi.stubGlobal('localStorage', { getItem:()=>{ throw Error('blocked') }, setItem:()=>{ throw Error('quota') } })
    bindPreferenceOwner(7)
    updatePreferences({theme:'blue-bowl',locale:'en'})
    bindPreferenceOwner(8)
    expect(getPreferences().theme).toBe('light')
    bindPreferenceOwner(7)
    expect(getPreferences()).toEqual({theme:'blue-bowl',locale:'en'})
  })
})
