import { useCallback, useEffect, useState } from 'react'
import { getTokenUsage, listUsers, type AdminUser, type TokenUsageRow } from '../../api/admin'

/**
 * Token 用量报表页（FR-A01：按用户/按天/按模型聚合）
 */
export default function TokenUsagePage() {
  const [rows, setRows] = useState<TokenUsageRow[]>([])
  const [users, setUsers] = useState<AdminUser[]>([])
  const [userId, setUserId] = useState<string>('')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    listUsers().then(setUsers).catch(() => setUsers([]))
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const params: { userId?: number; dateFrom?: string; dateTo?: string } = {}
      if (userId) params.userId = Number(userId)
      if (dateFrom) params.dateFrom = dateFrom
      if (dateTo) params.dateTo = dateTo
      setRows(await getTokenUsage(params))
    } catch (err) {
      setError(err instanceof Error ? err.message : '查询失败')
    } finally {
      setLoading(false)
    }
  }, [userId, dateFrom, dateTo])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="admin-page">
      <h2>Token 用量报表</h2>
      <div className="admin-filters">
        <label>
          用户
          <select value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">全部</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>{u.nickname || u.username}</option>
            ))}
          </select>
        </label>
        <label>
          起始日期
          <input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} />
        </label>
        <label>
          结束日期
          <input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} />
        </label>
        <button onClick={load} disabled={loading}>{loading ? '查询中…' : '查询'}</button>
      </div>
      {error && <div className="admin-error">{error}</div>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>用户</th>
            <th>日期</th>
            <th>模型</th>
            <th>消息数</th>
            <th>Token 消耗</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr><td colSpan={5} className="admin-empty">暂无数据（跑几轮对话后再看）</td></tr>
          ) : (
            rows.map((r, i) => (
              <tr key={i}>
                <td>{r.username}</td>
                <td>{r.date}</td>
                <td>{r.modelName}</td>
                <td>{r.messageCount}</td>
                <td>{r.totalTokens}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
