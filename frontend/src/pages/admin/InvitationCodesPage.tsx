import { usePreferences } from '../../preferences/PreferencesProvider'
import { t, ui } from '../../preferences/i18n'
import { useCallback, useEffect, useState } from 'react';
import { generateInvitationCode, listInvitationCodes, type InvitationCode, } from '../../api/admin';
/**
 * 邀请码管理页（2026-10-11 注册准入：发放 + 列表「码/使用人/使用时间」）
 */
export default function InvitationCodesPage() {
    usePreferences();
    const [codes, setCodes] = useState<InvitationCode[]>([]);
    const [error, setError] = useState('');
    const [generating, setGenerating] = useState(false);
    const load = useCallback(async () => {
        try {
            setCodes(await listInvitationCodes());
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '加载失败');
        }
    }, []);
    useEffect(() => {
        load();
    }, [load]);
    async function handleGenerate() {
        setGenerating(true);
        setError('');
        try {
            await generateInvitationCode();
            load();
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '发放失败');
        }
        finally {
            setGenerating(false);
        }
    }
    return (<div className="admin-page">
      <h2>{t("邀请码管理")}</h2>
      <div className="admin-filters">
        <button onClick={handleGenerate} disabled={generating}>
          {ui(generating ? t("发放中\u2026") : t("发放新邀请码"))}
        </button>
      </div>
      {ui(error && <div className="admin-error">{ui(error)}</div>)}
      <table className="admin-table">
        <thead>
          <tr>
            <th>{t("邀请码")}</th>
            <th>{t("状态")}</th>
            <th>{t("使用人 ID")}</th>
            <th>{t("使用时间")}</th>
            <th>{t("创建时间")}</th>
          </tr>
        </thead>
        <tbody>
          {ui(codes.length === 0 ? (<tr><td colSpan={5} className="admin-empty">{t("暂无邀请码\uFF0C点击上方按钮发放")}</td></tr>) : (codes.map((c) => (<tr key={c.id}>
                <td><code>{c.code}</code></td>
                <td>
                  <span className={`badge ${c.usedBy == null ? 'badge-on' : 'badge-off'}`}>
                    {ui(c.usedBy == null ? t("未使用") : t("已使用"))}
                  </span>
                </td>
                <td>{ui(c.usedBy ?? '-')}</td>
                <td>{ui(c.usedAt ? new Date(c.usedAt).toLocaleString() : '-')}</td>
                <td>{ui(new Date(c.createdAt).toLocaleString())}</td>
              </tr>))))}
        </tbody>
      </table>
    </div>);
}
