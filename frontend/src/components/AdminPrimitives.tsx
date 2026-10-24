import type { ReactNode } from 'react'
import { usePreferences } from '../preferences/PreferencesProvider'
import { ui } from '../preferences/i18n'
import './admin.css'

export function AdminPageHeader({ title, description, section, actions }: {
  title: string; description: string; section: string; actions?: ReactNode
}) {
  usePreferences()
  return <header className="admin-page-head"><div>
    <div className="admin-eyebrow">TRAVELSCOPE / {ui(section)}</div>
    <h1>{ui(title)}</h1><p>{ui(description)}</p>
  </div>{actions && <div className="admin-head-actions">{actions}</div>}</header>
}

export function AdminPanel({ title, subtitle, action, children, className = '' }: {
  title?: string; subtitle?: string; action?: ReactNode; children: ReactNode; className?: string
}) {
  usePreferences()
  return <section className={`admin-panel ${className}`}>
    {title && <header className="admin-section-head"><div><h2>{ui(title)}</h2>
      {subtitle && <p>{ui(subtitle)}</p>}</div>{action}</header>}{children}
  </section>
}

export function AdminTable({ children }: { children: ReactNode }) {
  return <div className="admin-table-wrap" tabIndex={0}>{children}</div>
}
