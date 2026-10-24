import { AdminPanel } from '../../../components/AdminPrimitives'
import { usePreferences } from '../../../preferences/PreferencesProvider'
import { t, ui } from '../../../preferences/i18n'
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { json, shortId, statusNames } from './model';
export function Badge({ status, label }: {
    status?: string;
    label?: string;
}) {
    usePreferences();
    const tone = ['PASS', 'SUCCEEDED', 'SUCCESS', 'COMPLETED', 'PLANNING_COMPLETED', 'REPLIED'].includes(status ?? '') ? 'positive'
        : ['FAILED', 'ERROR', 'FAIL', 'TIMED_OUT'].includes(status ?? '') ? 'negative'
            : ['RUNNING', 'QUEUED', 'PENDING', 'WAITING_INPUT', 'CANCELLING'].includes(status ?? '') ? 'active' : 'neutral';
    return <span className={`as-badge as-badge-${tone}`}>{ui(label ?? statusNames[status ?? ''] ?? t("未知状态"))}</span>;
}
export function Hint({ children, label = '指标说明' }: {
    children: ReactNode;
    label?: string;
}) {
    usePreferences();
    return <details className="as-hint"><summary aria-label={ui(label)}>?</summary><div>{children}</div></details>;
}
export function Empty({ title = '暂无记录', children }: {
    title?: string;
    children?: ReactNode;
}) {
    usePreferences();
    return <div className="as-empty"><span className="as-empty-mark" aria-hidden="true">—</span><h3>{ui(title)}</h3>{children && <p>{children}</p>}</div>;
}
export function ResourceState({ loading, error, reload, hasData = false }: {
    loading: boolean;
    error: string;
    reload: () => void;
    hasData?: boolean;
}) {
    usePreferences();
    return <>{ui(error && <div role="alert" className="as-notice as-notice-error"><span>{ui(error)}</span><button onClick={reload}>{t("重试")}</button></div>)}{ui(loading && !hasData && <div className="as-loading" role="status"><span />{t("正在加载\u2026")}</div>)}</>;
}
export function Panel({ title, subtitle, children, action }: {
    title: string;
    subtitle?: string;
    children: ReactNode;
    action?: ReactNode;
}) {
    usePreferences();
    return <AdminPanel className="as-panel" title={title} subtitle={subtitle} action={action}>{children}</AdminPanel>;
}
export function MetricRows({ items }: {
    items: {
        label: string;
        value: ReactNode;
        hint?: string;
    }[];
}) {
    usePreferences();
    return <dl className="as-metric-rows">{ui(items.map(item => <div key={item.label}><dt>{ui(item.label)}{ui(item.hint && <Hint label={ui(t("{0}说明", { "0": item.label }))}>{ui(item.hint)}</Hint>)}</dt><dd>{ui(item.value)}</dd></div>))}</dl>;
}
export function RawData({ value, title = '原始数据' }: {
    value: unknown;
    title?: string;
}) {
    usePreferences();
    return <details className="as-disclosure"><summary>{ui(title)}</summary><pre>{json(value)}</pre></details>;
}
export function CopyId({ value, label = '编号' }: {
    value?: string;
    label?: string;
}) {
    usePreferences();
    const [message, setMessage] = useState('');
    return <div className="as-copy"><span>{ui(label)}</span><code title={ui(value)}>{value || t("未记录")}</code>{ui(value && <button onClick={() => { void navigator.clipboard.writeText(value).then(() => setMessage(t("已复制")), () => setMessage(t("复制失败\uFF0C请选择编号手动复制"))); }}>{t("复制")}</button>)}<small role="status">{ui(message)}</small></div>;
}
export function Id({ value }: {
    value: string;
}) { usePreferences(); return <code title={ui(value)}>{shortId(value)}</code>; }
export function Overlay({ title, children, onClose, wide = false, modal = false, expandable = false }: {
    title: string;
    children: ReactNode;
    onClose: () => void;
    wide?: boolean;
    modal?: boolean;
    expandable?: boolean;
}) {
    usePreferences();
    const [fullscreen, setFullscreen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);
    const closeRef = useRef(onClose);
    useEffect(() => { closeRef.current = onClose; }, [onClose]);
    useEffect(() => {
        const previous = document.activeElement as HTMLElement | null;
        const focusable = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]') ?? []).filter(el => el.getClientRects().length > 0);
        const frame = requestAnimationFrame(() => { (focusable()[0] ?? ref.current)?.focus(); });
        function keydown(e: KeyboardEvent) {
            if (e.key === 'Escape') {
                e.preventDefault();
                closeRef.current();
            }
            if (e.key === 'Tab') {
                const nodes = focusable(), first = nodes[0], last = nodes.at(-1);
                if (!first) {
                    e.preventDefault();
                    ref.current?.focus();
                    return;
                }
                if (e.shiftKey && (document.activeElement === first || document.activeElement === ref.current)) {
                    e.preventDefault();
                    last?.focus();
                }
                else if (!e.shiftKey && document.activeElement === last) {
                    e.preventDefault();
                    first.focus();
                }
            }
        }
        const priorOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        document.addEventListener('keydown', keydown);
        return () => { cancelAnimationFrame(frame); document.body.style.overflow = priorOverflow; document.removeEventListener('keydown', keydown); if (previous?.isConnected)
            previous.focus(); };
    }, []);
    return createPortal(<div className={`assessment as-overlay ${modal ? 'as-overlay-modal' : ''}`} onMouseDown={e => { if (e.target === e.currentTarget)
        onClose(); }}><div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={ui(title)} className={`as-drawer ${wide ? 'as-drawer-wide' : ''} ${fullscreen ? 'as-drawer-fullscreen' : ''}`}><header className="as-drawer-head"><h2>{ui(title)}</h2><div className="as-button-row">{ui(expandable && <button aria-pressed={fullscreen} onClick={() => setFullscreen(value => !value)}>{ui(fullscreen ? t("退出全屏") : t("展开为全屏"))}</button>)}<button aria-label={ui(t("关闭{0}", { "0": title }))} onClick={onClose}>{t("关闭")}<span aria-hidden="true">×</span></button></div></header><div className="as-drawer-body">{children}</div></div></div>, document.body);
}
