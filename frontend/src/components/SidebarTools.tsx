import { usePreferences } from '../preferences/PreferencesProvider'
import { updatePreferences } from '../preferences/store'
import { t } from '../preferences/i18n'

export default function SidebarTools() {
  const { theme, locale } = usePreferences()
  return <div className="sidebar-tools" role="group" aria-label={t('显示偏好')}>
    <span title={t('暂未开放')}><button type="button" disabled aria-label={t('设置：暂未开放')}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 3-1 3-3 1-2 4 2 2v4l4 3 3-1 3 1 4-3v-4l2-2-2-4-3-1-1-3Z"/><circle cx="12" cy="12" r="3"/></svg><span>{t('设置')}</span>
    </button></span>
    <button type="button" aria-pressed={theme === 'dark'} title={t(theme === 'light' ? '切换到深色模式' : '切换到浅色模式')} aria-label={t(theme === 'light' ? '切换到深色模式' : '切换到浅色模式')} onClick={() => updatePreferences({ theme: theme === 'light' ? 'dark' : 'light' })}>
      <svg viewBox="0 0 24 24" aria-hidden="true">{theme === 'light' ? <><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></> : <path d="M20 15.5A9 9 0 0 1 8.5 4 9 9 0 1 0 20 15.5Z"/>}</svg><span>{t('主题')}</span>
    </button>
    <button type="button" title={t(locale === 'zh-CN' ? '切换到英文' : '切换到中文')} aria-label={t(locale === 'zh-CN' ? '切换到英文' : '切换到中文')} onClick={() => updatePreferences({ locale: locale === 'zh-CN' ? 'en' : 'zh-CN' })}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 5h12M9 2v3M5 5c0 5 4 9 9 10M13 5c0 5-4 9-10 11m11 5 4-11 4 11m-6.5-4h5"/></svg><span className="sidebar-language-label">{t('语言')}<small aria-hidden="true">{locale === 'zh-CN' ? '中文' : 'EN'}</small></span>
    </button>
  </div>
}
