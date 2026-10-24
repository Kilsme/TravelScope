import { useEffect, useId, useRef, useState } from 'react'
import { usePreferences } from '../preferences/PreferencesProvider'
import { themes, updatePreferences } from '../preferences/store'
import type { Theme } from '../preferences/store'
import { ui } from '../preferences/i18n'
import PixelIcon from './PixelIcon'

const themeNames: Record<Theme, string> = { light:'浅色', dark:'深色', peach:'水蜜桃', 'blue-bowl':'蓝白碗' }
export default function ThemePicker() {
  const { theme } = usePreferences()
  const [open, setOpen] = useState(false)
  const id = useId()
  const root = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null)
  const items = useRef<(HTMLButtonElement | null)[]>([])
  const [focused, setFocused] = useState(0)
  useEffect(() => {
    if (!open) return
    items.current[focused]?.focus()
    function outside(event: PointerEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open, focused])
  function close() { setOpen(false); trigger.current?.focus() }
  return <div className="theme-picker" ref={root} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }}>
    <button type="button" ref={trigger} aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined}
      aria-label={`${ui('主题')}：${ui(themeNames[theme])}`} title={ui(themeNames[theme])}
      onClick={() => { setFocused(themes.indexOf(theme)); setOpen(value => !value) }}
      onKeyDown={event => {
        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
          event.preventDefault(); setFocused(themes.indexOf(theme)); setOpen(true)
        }
      }}><PixelIcon name="palette" /><span>{ui('主题')}</span></button>
    {open && <div id={id} className="theme-menu" role="menu" aria-label={ui('主题')} onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
      if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) {
        event.preventDefault()
        setFocused(value => event.key === 'Home' ? 0 : event.key === 'End' ? themes.length - 1 :
          (value + (event.key === 'ArrowDown' ? 1 : -1) + themes.length) % themes.length)
      }
    }}>
      {themes.map((value, index) => <button key={value} type="button" role="menuitemradio"
        ref={node => { items.current[index] = node }} tabIndex={focused === index ? 0 : -1}
        aria-checked={theme === value} onFocus={() => setFocused(index)}
        onClick={() => { updatePreferences({ theme:value }); close() }}>
        <span className={`theme-swatch theme-swatch-${value}`} aria-hidden="true"><i /><i /><i /></span>
        <span>{ui(themeNames[value])}</span>{theme === value && <PixelIcon name="check" />}
      </button>)}
    </div>}
  </div>
}
