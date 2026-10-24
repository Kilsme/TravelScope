import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { apiFetch, clearAuth, login } from '../api/auth'
import { bindPreferenceOwner, getPreferenceOwner, getPreferences, handlePreferenceStorage, updatePreferences } from './store'

const values = new Map<string,string>()
beforeEach(() => {
  values.clear()
  vi.stubGlobal('localStorage', {
    getItem:(key:string)=>values.get(key) ?? null,
    setItem:(key:string,value:string)=>values.set(key,value),
    removeItem:(key:string)=>values.delete(key),
  })
  vi.stubGlobal('location', {pathname:'/login'})
  handlePreferenceStorage({key:null,newValue:null})
})
afterEach(() => { values.clear();handlePreferenceStorage({key:null,newValue:null});vi.unstubAllGlobals() })

it('binds login to its identity and detaches on authentication clear', async () => {
  bindPreferenceOwner(20)
  updatePreferences({theme:'peach'})
  bindPreferenceOwner(null)
  vi.stubGlobal('fetch',vi.fn(async()=>Response.json({userId:20,token:'test',role:'user',nickname:'test'})))
  await login('test','fixture')
  expect(getPreferenceOwner()).toBe(20)
  expect(getPreferences().theme).toBe('peach')
  clearAuth()
  expect(getPreferenceOwner()).toBeNull()
  expect(getPreferences().theme).toBe('light')
  expect(values.has('travelscope_user_id')).toBe(false)
})

it('does not let a late 401 from the old account clear the new account', async () => {
  values.set('travelscope_token','old')
  let complete!: (response:Response)=>void
  vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(resolve=>{complete=resolve})))
  const oldRequest=apiFetch('/api/auth/me')
  values.set('travelscope_token','new')
  bindPreferenceOwner(21)
  updatePreferences({theme:'blue-bowl'})
  complete(new Response(null,{status:401}))
  await oldRequest
  expect(values.get('travelscope_token')).toBe('new')
  expect(getPreferenceOwner()).toBe(21)
  expect(getPreferences().theme).toBe('blue-bowl')
})

it('detaches preferences when the active account receives 401', async () => {
  values.set('travelscope_token','active')
  values.set('travelscope_user_id','22')
  bindPreferenceOwner(22)
  updatePreferences({theme:'dark'})
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(null,{status:401})))
  await apiFetch('/api/admin/users')
  expect(getPreferenceOwner()).toBeNull()
  expect(getPreferences().theme).toBe('light')
  expect(values.has('travelscope_token')).toBe(false)
})
