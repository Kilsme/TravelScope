import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { getNickname, logout } from '../../api/auth'

/**
 * 管理端布局：侧边导航 + 内容区（FR-A01~A03）
 */
export default function AdminLayout() {
  const navigate = useNavigate()
  const nickname = getNickname()

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="admin-layout">
      <aside className="admin-sidebar">
        <div className="admin-brand">TravelScope 管理端</div>
        <nav className="admin-nav">
          <NavLink to="/admin/token-usage" className={({ isActive }) => isActive ? 'active' : ''}>
            📊 Token 用量
          </NavLink>
          <NavLink to="/admin/users" className={({ isActive }) => isActive ? 'active' : ''}>
            👥 用户管理
          </NavLink>
          <NavLink to="/admin/documents" className={({ isActive }) => isActive ? 'active' : ''}>
            📄 文档上传
          </NavLink>
        </nav>
        <div className="admin-user">
          <span className="admin-nickname">{nickname || 'admin'}</span>
          <button className="admin-logout" onClick={handleLogout}>退出登录</button>
        </div>
      </aside>
      <main className="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
