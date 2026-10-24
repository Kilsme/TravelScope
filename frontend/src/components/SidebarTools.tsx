import { usePreferences } from '../preferences/PreferencesProvider'
import { updatePreferences } from '../preferences/store'
import { t } from '../preferences/i18n'
import PixelIcon from './PixelIcon'
import ThemePicker from './ThemePicker'

export default function SidebarTools({ admin = false }: { admin?: boolean }) {
  const { locale } = usePreferences()
  return <div className={`sidebar-tools ${admin ? 'sidebar-tools-admin' : ''}`} role="group" aria-label={t('显示偏好')}>
    {!admin && <span title={t('暂未开放')}><button type="button" disabled aria-label={t('设置：暂未开放')}>
      <PixelIcon name="settings" /><span>{t('设置')}</span>
    </button></span>}
    <ThemePicker />
    <button type="button" title={t(locale === 'zh-CN' ? '切换到英文' : '切换到中文')} aria-label={t(locale === 'zh-CN' ? '切换到英文' : '切换到中文')} onClick={() => updatePreferences({ locale: locale === 'zh-CN' ? 'en' : 'zh-CN' })}>
      <PixelIcon name="language" /><span className="sidebar-language-label">{t('语言')}<small aria-hidden="true">{locale === 'zh-CN' ? '中文' : 'EN'}</small></span>
    </button>
  </div>
}
