import { useCallback, useEffect, useState } from 'react'
import { disableUser, enableUser, listUsers, setUserRole, type AdminUser } from '../../api/admin'

/**
 * 用户管理页（FR-A02：列表/禁用/启用/角色）
 */
export default function UsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([])
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setUsers(await listUsers())
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function handleDisable(id: number) {
    try {
      await disableUser(id)
      load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '禁用失败')
    }
  }

  async function handleEnable(id: number) {
    try {
      await enableUser(id)
      load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '启用失败')
    }
  }

  async function handleRole(id: number, role: 'user' | 'admin') {
    try {
      await setUserRole(id, role)
      load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '修改失败')
    }
  }

  return (
    <div className="admin-page">
      <h2>用户管理</h2>
      {error && <div className="admin-error">{error}</div>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>用户名</th>
            <th>昵称</th>
            <th>角色</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {users.length === 0 ? (
            <tr><td colSpan={6} className="admin-empty">暂无用户</td></tr>
          ) : (
            users.map((u) => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>{u.username}</td>
                <td>{u.nickname || '-'}</td>
                <td>
                  <span className={`badge ${u.role === 'admin' ? 'badge-admin' : 'badge-user'}`}>
                    {u.role}
                  </span>
                </td>
                <td>
                  <span className={`badge ${u.status === 1 ? 'badge-on' : 'badge-off'}`}>
                    {u.status === 1 ? '启用' : '禁用'}
                  </span>
                </td>
                <td className="admin-actions">
                  {u.status === 1 ? (
                    <button className="btn-danger" onClick={() => handleDisable(u.id)}>禁用</button>
                  ) : (
                    <button className="btn-primary" onClick={() => handleEnable(u.id)}>启用</button>
                  )}
                  <button
                    className="btn-ghost"
                    onClick={() => handleRole(u.id, u.role === 'admin' ? 'user' : 'admin')}
                  >
                    改为{u.role === 'admin' ? 'user' : 'admin'}
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
