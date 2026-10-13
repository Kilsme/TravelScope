import { useState } from 'react'
import { Link } from 'react-router-dom'
import { register } from '../api/auth'

/**
 * 注册页（2026-10-11 邀请码准入：仅凭有效邀请码开放，注册成功后再登录）
 */
export default function RegisterPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [invitationCode, setInvitationCode] = useState('')
  const [error, setError] = useState('')
  const [done, setDone] = useState(false)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await register(username, password, nickname, invitationCode)
      setDone(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      {done ? (
        <div className="login-card">
          <h2>注册成功</h2>
          <p className="login-link">账号已创建，初始积分已到账，请登录使用。</p>
          <Link to="/login">
            <button type="button">去登录</button>
          </Link>
        </div>
      ) : (
        <form className="login-card" onSubmit={handleSubmit}>
          <h2>TravelScope 注册</h2>
          {error && <div className="login-error">{error}</div>}
          <label>
            用户名
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="请输入用户名"
              autoComplete="username"
              required
            />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              autoComplete="new-password"
              required
            />
          </label>
          <label>
            昵称（可选）
            <input
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              placeholder="默认与用户名相同"
              autoComplete="nickname"
            />
          </label>
          <label>
            邀请码
            <input
              value={invitationCode}
              onChange={(e) => setInvitationCode(e.target.value)}
              placeholder="请输入 8 位邀请码"
              required
            />
          </label>
          <button type="submit" disabled={loading}>
            {loading ? '注册中…' : '注册'}
          </button>
          <p className="login-link">
            已有账号？<Link to="/login">去登录</Link>
          </p>
        </form>
      )}
    </div>
  )
}
