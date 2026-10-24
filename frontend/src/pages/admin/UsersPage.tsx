import { AdminPageHeader, AdminPanel, AdminTable } from '../../components/AdminPrimitives'
import { usePreferences } from '../../preferences/PreferencesProvider'
import { t, ui } from '../../preferences/i18n'
import { useCallback, useEffect, useState } from 'react';
import { adjustPoints, disableUser, enableUser, listUsers, setUserRole, type AdminUser } from '../../api/admin';
/**
 * 用户管理页（FR-A02：列表/禁用/启用/角色；2026-10-11 积分制度：余额展示 + 行内发放/回收）
 */
export default function UsersPage() {
    usePreferences();
    const [users, setUsers] = useState<AdminUser[]>([]);
    const [error, setError] = useState('');
    // 行内积分调整输入（key=userId；正数发放、负数回收）
    const [pointsDelta, setPointsDelta] = useState<Record<number, string>>({});
    const load = useCallback(async () => {
        try {
            setUsers(await listUsers());
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '加载失败');
        }
    }, []);
    useEffect(() => {
        load();
    }, [load]);
    async function handleDisable(id: number) {
        try {
            await disableUser(id);
            load();
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '禁用失败');
        }
    }
    async function handleEnable(id: number) {
        try {
            await enableUser(id);
            load();
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '启用失败');
        }
    }
    async function handleRole(id: number, role: 'user' | 'admin') {
        try {
            await setUserRole(id, role);
            load();
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '修改失败');
        }
    }
    async function handlePoints(id: number) {
        const delta = Number(pointsDelta[id]);
        if (!Number.isInteger(delta) || delta === 0) {
            setError('积分调整量需为非零整数（正数发放、负数回收）');
            return;
        }
        try {
            await adjustPoints(id, delta);
            setPointsDelta((prev) => ({ ...prev, [id]: '' }));
            load();
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '调整积分失败');
        }
    }
    return (<div className="admin-page">
      <AdminPageHeader title="用户管理" section="账户与权限" description="管理用户状态、角色与积分余额。" />
      {ui(error && <div className="admin-error">{ui(error)}</div>)}
      <AdminPanel title="用户列表"><AdminTable>
      <table className="admin-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>{t("用户名")}</th>
            <th>{t("昵称")}</th>
            <th>{t("角色")}</th>
            <th>{t("状态")}</th>
            <th>{t("积分余额")}</th>
            <th>{t("操作")}</th>
          </tr>
        </thead>
        <tbody>
          {ui(users.length === 0 ? (<tr><td colSpan={7} className="admin-empty">{t("暂无用户")}</td></tr>) : (users.map((u) => (<tr key={u.id}>
                <td>{ui(u.id)}</td>
                <td>{u.username}</td>
                <td>{u.nickname || '-'}</td>
                <td>
                  <span className={`badge ${u.role === 'admin' ? 'badge-admin' : 'badge-user'}`}>
                    {ui(u.role)}
                  </span>
                </td>
                <td>
                  <span className={`badge ${u.status === 1 ? 'badge-on' : 'badge-off'}`}>
                    {ui(u.status === 1 ? t("启用") : t("禁用"))}
                  </span>
                </td>
                <td>
                  {ui(u.pointsBalance ?? 0)}
                  <span className="points-grant">
                    <input type="number" step="1" placeholder={t("\u00B1积分")} value={pointsDelta[u.id] ?? ''} onChange={(e) => setPointsDelta((prev) => ({ ...prev, [u.id]: e.target.value }))}/>
                    <button className="btn-primary" onClick={() => handlePoints(u.id)}>{t("调整")}</button>
                  </span>
                </td>
                <td className="admin-actions">
                  {ui(u.status === 1 ? (<button className="btn-danger" onClick={() => handleDisable(u.id)}>{t("禁用")}</button>) : (<button className="btn-primary" onClick={() => handleEnable(u.id)}>{t("启用")}</button>))}
                  <button className="btn-ghost" onClick={() => handleRole(u.id, u.role === 'admin' ? 'user' : 'admin')}>{t("改为")}{ui(u.role === 'admin' ? 'user' : 'admin')}
                  </button>
                </td>
              </tr>))))}
        </tbody>
      </table>
      </AdminTable></AdminPanel>
    </div>);
}
