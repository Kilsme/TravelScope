import { useCallback, useEffect, useState } from 'react'
import {
  generateInvitationCode,
  listInvitationCodes,
  type InvitationCode,
} from '../../api/admin'

/**
 * 邀请码管理页（2026-10-11 注册准入：发放 + 列表「码/使用人/使用时间」）
 */
export default function InvitationCodesPage() {
  const [codes, setCodes] = useState<InvitationCode[]>([])
  const [error, setError] = useState('')
  const [generating, setGenerating] = useState(false)

  const load = useCallback(async () => {
    try {
      setCodes(await listInvitationCodes())
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function handleGenerate() {
    setGenerating(true)
    setError('')
    try {
      await generateInvitationCode()
      load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '发放失败')
    } finally {
      setGenerating(false)
    }
  }

  return (
    <div className="admin-page">
      <h2>邀请码管理</h2>
      <div className="admin-filters">
        <button onClick={handleGenerate} disabled={generating}>
          {generating ? '发放中…' : '发放新邀请码'}
        </button>
      </div>
      {error && <div className="admin-error">{error}</div>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>邀请码</th>
            <th>状态</th>
            <th>使用人 ID</th>
            <th>使用时间</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          {codes.length === 0 ? (
            <tr><td colSpan={5} className="admin-empty">暂无邀请码，点击上方按钮发放</td></tr>
          ) : (
            codes.map((c) => (
              <tr key={c.id}>
                <td><code>{c.code}</code></td>
                <td>
                  <span className={`badge ${c.usedBy == null ? 'badge-on' : 'badge-off'}`}>
                    {c.usedBy == null ? '未使用' : '已使用'}
                  </span>
                </td>
                <td>{c.usedBy ?? '-'}</td>
                <td>{c.usedAt ? new Date(c.usedAt).toLocaleString() : '-'}</td>
                <td>{new Date(c.createdAt).toLocaleString()}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
