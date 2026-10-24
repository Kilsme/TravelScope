import { AdminPageHeader, AdminPanel, AdminTable } from '../../components/AdminPrimitives'
import { usePreferences } from '../../preferences/PreferencesProvider'
import { t, ui } from '../../preferences/i18n'
import { useCallback, useEffect, useState } from 'react';
import { getTokenUsage, listUsers, type AdminUser, type TokenUsageRow } from '../../api/admin';
/**
 * Token 用量报表页（FR-A01：按用户/按天/按模型聚合）
 */
export default function TokenUsagePage() {
    usePreferences();
    const [rows, setRows] = useState<TokenUsageRow[]>([]);
    const [users, setUsers] = useState<AdminUser[]>([]);
    const [userId, setUserId] = useState<string>('');
    const [dateFrom, setDateFrom] = useState('');
    const [dateTo, setDateTo] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    useEffect(() => {
        listUsers().then(setUsers).catch(() => setUsers([]));
    }, []);
    const load = useCallback(async () => {
        setLoading(true);
        setError('');
        try {
            const params: {
                userId?: number;
                dateFrom?: string;
                dateTo?: string;
            } = {};
            if (userId)
                params.userId = Number(userId);
            if (dateFrom)
                params.dateFrom = dateFrom;
            if (dateTo)
                params.dateTo = dateTo;
            setRows(await getTokenUsage(params));
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '查询失败');
        }
        finally {
            setLoading(false);
        }
    }, [userId, dateFrom, dateTo]);
    useEffect(() => {
        load();
    }, [load]);
    return (<div className="admin-page">
      <AdminPageHeader title="Token 用量报表" section="用量与成本" description="按用户、日期与模型查看 Token 消耗。" />
      <AdminPanel title="筛选条件">
      <div className="admin-filters">
        <label>{t("用户")}<select value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">{t("全部")}</option>
            {ui(users.map((u) => (<option key={u.id} value={u.id}>{u.nickname || u.username}</option>)))}
          </select>
        </label>
        <label>{t("起始日期")}<input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)}/>
        </label>
        <label>{t("结束日期")}<input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)}/>
        </label>
        <button onClick={load} disabled={loading}>{ui(loading ? t("查询中\u2026") : t("查询"))}</button>
      </div>
      </AdminPanel>
      {ui(error && <div role="alert" className="admin-error">{ui(error)}</div>)}
      <AdminPanel title="用量明细"><AdminTable>
      <table className="admin-table">
        <thead>
          <tr>
            <th>{t("用户")}</th>
            <th>{t("日期")}</th>
            <th>{t("模型")}</th>
            <th>{t("消息数")}</th>
            <th>{t("Token 消耗")}</th>
          </tr>
        </thead>
        <tbody>
          {ui(rows.length === 0 ? (<tr><td colSpan={5} className="admin-empty">{t("暂无数据\uFF08跑几轮对话后再看\uFF09")}</td></tr>) : (rows.map((r, i) => (<tr key={i}>
                <td>{r.username}</td>
                <td>{ui(r.date)}</td>
                <td>{r.modelName}</td>
                <td>{ui(r.messageCount)}</td>
                <td>{ui(r.totalTokens)}</td>
              </tr>))))}
        </tbody>
      </table>
      </AdminTable></AdminPanel>
    </div>);
}
