import { afterEach, describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { PreferencesProvider } from './PreferencesProvider'
import SidebarTools from '../components/SidebarTools'
import MarkdownView from '../components/MarkdownView'
import { fieldLabel, optionLabel } from './formText'
import { getPreferences, parsePreferences, updatePreferences } from './store'
import { messages } from './messages'
import { t, ui, uiMessage, resolveMessage } from './i18n'

afterEach(() => updatePreferences({ theme: 'light', locale: 'zh-CN' }, false))
describe('appearance and UI language', () => {
  it('uses safe defaults for missing, malformed and invalid storage', () => {
    for (const raw of [null, '{broken', 'null', '42', '{"theme":"system","locale":"fr"}'])
      expect(parsePreferences(raw)).toEqual({ theme:'light', locale:'zh-CN' })
    expect(parsePreferences('{"theme":"dark","locale":"en"}')).toEqual({theme:'dark',locale:'en'})
  })
  it('renders all theme/language combinations without enabling settings', () => {
    for (const theme of ['light','dark','peach','blue-bowl'] as const) for (const locale of ['zh-CN','en'] as const) {
      updatePreferences({theme,locale},false)
      const html=renderToStaticMarkup(<PreferencesProvider><SidebarTools /></PreferencesProvider>)
      expect(html).toContain('disabled=""')
      expect(html).toContain(locale==='en'?'Settings':'设置')
      expect(html).toContain('aria-haspopup="menu"')
      expect(html).toContain('aria-expanded="false"')
      expect(html).not.toMatch(/<[^>]*>[^<]*[🌍📷⚙]/u)
    }
  })
  it('retranslates stored notices and formats arguments without modifying unknown prose', () => {
    const notice=uiMessage('连接中断，正在恢复原任务…')
    expect(notice.key).toBeDefined()
    updatePreferences({locale:'en'},false)
    expect(resolveMessage(notice)).toContain('restoring')
    expect(t('第 {0} 次',{'0':2})).toBe('Attempt 2')
    expect(ui(' · 需求处理')).toBe(' · Request handling')
    expect(ui('用户自定义原文')).toBe('用户自定义原文')
    updatePreferences({locale:'zh-CN'},false)
    expect(resolveMessage(notice)).toBe('连接中断，正在恢复原任务…')
  })
  it('has translations and matching interpolation slots for every key', () => {
    for (const [key,value] of Object.entries(messages)) {
      expect(value.length,key).toBeGreaterThan(0)
      expect([...value.matchAll(/\{\d+\}/g)].map(m=>m[0]).sort(),key).toEqual([...key.matchAll(/\{\d+\}/g)].map(m=>m[0]).sort())
    }
    expect(getPreferences().locale).toBe('zh-CN')
  })
  it('preserves historical body text and localizes form labels without changing submitted values', () => {
    updatePreferences({locale:'en'},false)
    expect(renderToStaticMarkup(<MarkdownView text="取消" />)).toContain('取消')
    const field={name:'preference',label:'偏好',labelKey:'requirements.preference.label',type:'select',value:'自然风景',filled:true,required:false,options:['自然风景'],placeholder:'',optionKeys:{'自然风景':'requirements.preference.option.0'}}
    expect(fieldLabel(field)).toBe('Preferences')
    expect(optionLabel(field,'自然风景')).toBe('Nature')
    expect(field.value).toBe('自然风景')
    const notice=uiMessage('已复制')
    expect(resolveMessage(notice)).toBe('Copied')
    updatePreferences({locale:'zh-CN'},false)
    expect(resolveMessage(notice)).toBe('已复制')
  })
})
