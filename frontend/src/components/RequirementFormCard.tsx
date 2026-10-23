import { fieldLabel, fieldPlaceholder, optionLabel } from '../preferences/formText'
import { usePreferences } from '../preferences/PreferencesProvider'
import { t, ui } from '../preferences/i18n'
import { useEffect, useMemo, useState } from 'react';
import type { RequirementFormField, RequirementFormPayload } from '../types';
/**
 * 需求清单卡片（S2.3 清单卡片方案）：
 * - 抽取结果全部预填展示，已填行可编辑、缺失行高亮（必填带 *）
 * - 控件按 type 渲染：text / number / date / select / multi-select（多选 chip + 自定义）
 * - geocode 打回的字段标红并显示错误文案
 * - 三态动作：开始规划（提交）/ 按推荐安排（留空走默认，后端 DEGRADED）/ 取消规划
 * - 输入框不锁定：卡片之外用户仍可打字（走后端增量修改分支，卡片原地刷新）
 */
interface Props {
    payload: RequirementFormPayload;
    submitted: boolean;
    disabled: boolean;
    onAction: (action: 'submit' | 'skip' | 'cancel', values: Record<string, string>) => void;
}
/** 提交体字段值：只提交非空项（数字字段原样字符串，后端强转） */
function collectValues(fields: RequirementFormField[], edited: Record<string, string>) {
    const values: Record<string, string> = {};
    for (const f of fields) {
        const v = (edited[f.name] ?? f.value ?? '').trim();
        if (v)
            values[f.name] = v;
    }
    return values;
}
function RequirementFormCard({ payload, submitted, disabled, onAction }: Props) {
    usePreferences();
    const [edited, setEdited] = useState<Record<string, string>>({});
    // 收到新载荷（原地刷新）时以服务端值重置编辑态
    useEffect(() => {
        setEdited({});
    }, [payload]);
    const errorByField = useMemo(() => {
        const map: Record<string, string> = {};
        for (const e of payload.errors ?? [])
            map[e.field] = e.message;
        return map;
    }, [payload]);
    const missingRequired = payload.fields.filter((f) => f.required && !(edited[f.name] ?? f.value ?? '').trim());
    const setValue = (name: string, value: string) => setEdited((prev) => ({ ...prev, [name]: value }));
    if (submitted) {
        return (<div className="req-form submitted">
        <div className="req-form-title">{t("\u2705 需求已确认")}</div>
        <div className="req-form-summary">
          {payload.fields
                .filter((f) => (f.value ?? '').trim())
                .map((f) => `${fieldLabel(f)}: ${f.value}`)
                .join('　')}
        </div>
      </div>);
    }
    return (<div className="req-form">
      <div className="req-form-title">{t("\uD83D\uDCCB 确认行程需求")}</div>
      {ui(payload.hint && <div className="req-form-hint">💡 {ui(payload.hint)}</div>)}
      <div className="req-form-rows">
        {payload.fields.map((f) => {
            const value = edited[f.name] ?? f.value ?? '';
            const error = errorByField[f.name];
            const missing = f.required && !value.trim();
            return (<div key={f.name} className={`req-form-row ${missing ? 'missing' : ''} ${error ? 'error' : ''}`}>
              <label className="req-form-label">
                {fieldLabel(f)}
                {ui(f.required && <span className="req-form-required">*</span>)}
              </label>
              <div className="req-form-control">
                {ui(renderControl(f, value, disabled, setValue))}
                {ui(error ? (<div className="req-form-error">⚠️ {ui(error)}</div>) : (<div className="req-form-placeholder">{fieldPlaceholder(f)}</div>))}
              </div>
            </div>);
        })}
      </div>
      <div className="req-form-actions">
        <button className="req-btn primary" disabled={disabled || missingRequired.length > 0} title={ui(missingRequired.length > 0 ? t("请先填写带 * 的必填项") : t("按填写内容开始规划"))} onClick={() => onAction('submit', collectValues(payload.fields, edited))}>{t("开始规划")}</button>
        <button className="req-btn" disabled={disabled} title={t("缺失项按推荐默认值处理")} onClick={() => onAction('skip', collectValues(payload.fields, edited))}>{t("按推荐安排")}</button>
        <button className="req-btn ghost" disabled={disabled} onClick={() => onAction('cancel', {})}>{t("取消规划")}</button>
      </div>
      {ui(missingRequired.length > 0 && (<div className="req-form-note">{t("还可补充\uFF1A")}{ui(missingRequired.map((f) => fieldLabel(f)).join('、'))}
        </div>))}
    </div>);
}
function renderControl(f: RequirementFormField, value: string, disabled: boolean, setValue: (name: string, value: string) => void) {
    switch (f.type) {
        case 'number':
            return (<input type="number" min={1} value={value} disabled={disabled} placeholder={fieldPlaceholder(f)} onChange={(e) => setValue(f.name, e.target.value)}/>);
        case 'date':
            return (<input type="date" value={value} disabled={disabled} onChange={(e) => setValue(f.name, e.target.value)}/>);
        case 'select':
            return (<select value={value} disabled={disabled} onChange={(e) => setValue(f.name, e.target.value)}>
          <option value="">{t("请选择\u2026")}</option>
          {ui(f.options.map((o) => (<option key={o} value={o}>
              {optionLabel(f,o)}
            </option>)))}
        </select>);
        case 'multi-select': {
            // 已选项 = 选项命中的部分；剩余文本按「自定义」保留（用「、」分隔）
            const selected = f.options.filter((o) => value.includes(o));
            const custom = value
                .split(/[、,，]/)
                .map((s) => s.trim())
                .filter((s) => s && !f.options.includes(s))
                .join('、');
            const toggle = (o: string) => {
                const next = selected.includes(o) ? selected.filter((x) => x !== o) : [...selected, o];
                setValue(f.name, [...next, custom].filter(Boolean).join('、'));
            };
            return (<div className="req-chips">
          {ui(f.options.map((o) => (<button key={o} type="button" className={`req-chip ${selected.includes(o) ? 'active' : ''}`} disabled={disabled} onClick={() => toggle(o)}>
              {optionLabel(f,o)}
            </button>)))}
          <input className="req-chip-custom" value={custom} disabled={disabled} placeholder={t("其他偏好\u2026")} onChange={(e) => {
                    const other = e.target.value;
                    setValue(f.name, [...selected, other].filter(Boolean).join('、'));
                }}/>
        </div>);
        }
        default:
            return (<input type="text" value={value} disabled={disabled} placeholder={fieldPlaceholder(f)} onChange={(e) => setValue(f.name, e.target.value)}/>);
    }
}
export default RequirementFormCard;
