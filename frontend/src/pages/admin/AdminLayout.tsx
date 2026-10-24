import SidebarTools from '../../components/SidebarTools'
import PixelLabel from '../../components/PixelLabel'
import { usePreferences } from '../../preferences/PreferencesProvider'
import { t } from '../../preferences/i18n'
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { getNickname, logout } from '../../api/auth';
/**
 * 管理端布局：侧边导航 + 内容区（FR-A01~A03）
 */
export default function AdminLayout() {
    usePreferences();
    const navigate = useNavigate();
    const nickname = getNickname();
    async function handleLogout() {
        await logout();
        navigate('/login', { replace: true });
    }
    return (<div className="admin-layout">
      <aside className="admin-sidebar">
        <div className="admin-brand">{t("TravelScope 管理端")}</div>
        <nav className="admin-nav">
          <NavLink to="/admin/assessment" className={({ isActive }) => isActive ? 'active' : ''}>{<PixelLabel text={t("\uD83E\uDDEA 评估中心")} />}</NavLink>
          <NavLink to="/admin/token-usage" className={({ isActive }) => isActive ? 'active' : ''}>{<PixelLabel text={t("\uD83D\uDCCA Token 用量")} />}</NavLink>
          <NavLink to="/admin/users" className={({ isActive }) => isActive ? 'active' : ''}>{<PixelLabel text={t("\uD83D\uDC65 用户管理")} />}</NavLink>
          <NavLink to="/admin/documents" className={({ isActive }) => isActive ? 'active' : ''}>{<PixelLabel text={t("\uD83D\uDCC4 文档上传")} />}</NavLink>
          <NavLink to="/admin/invitation-codes" className={({ isActive }) => isActive ? 'active' : ''}>{<PixelLabel text={t("\uD83C\uDFAB 邀请码")} />}</NavLink>
        </nav>
        <SidebarTools admin />
        <div className="admin-user">
          <span className="admin-nickname">{nickname || 'admin'}</span>
          <button className="admin-logout" onClick={handleLogout}>{t("退出登录")}</button>
        </div>
      </aside>
      <main className="admin-main">
        <Outlet />
      </main>
    </div>);
}
