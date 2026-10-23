import { usePreferences } from './preferences/PreferencesProvider'
import { t } from './preferences/i18n'
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './App.css';
import './preferences/styles.css';
import { getRole, isLoggedIn } from './api/auth';
import ChatPage from './pages/ChatPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
const AssessmentPage = lazy(() => import('./pages/admin/AssessmentPage'));
import AdminLayout from './pages/admin/AdminLayout';
import TokenUsagePage from './pages/admin/TokenUsagePage';
import UsersPage from './pages/admin/UsersPage';
import DocUploadPage from './pages/admin/DocUploadPage';
import InvitationCodesPage from './pages/admin/InvitationCodesPage';
/**
 * 受保护路由：未登录 → /login
 */
function RequireAuth({ children }: {
    children: React.ReactNode;
}) {
    usePreferences();
    return isLoggedIn() ? <>{children}</> : <Navigate to="/login" replace/>;
}
/**
 * 管理员路由：非 admin → 跳聊天页
 */
function RequireAdmin({ children }: {
    children: React.ReactNode;
}) {
    usePreferences();
    if (!isLoggedIn())
        return <Navigate to="/login" replace/>;
    if (getRole() !== 'admin')
        return <Navigate to="/" replace/>;
    return <>{children}</>;
}
export default function App() {
    usePreferences();
    return (<BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />}/>
        <Route path="/register" element={<RegisterPage />}/>
        <Route path="/" element={<RequireAuth>
              <ChatPage />
            </RequireAuth>}/>
        <Route path="/admin" element={<RequireAdmin>
              <AdminLayout />
            </RequireAdmin>}>
          <Route index element={<Navigate to="token-usage" replace/>}/>
          <Route path="assessment" element={<Suspense fallback={<p role="status">{t("正在加载评估中心…")}</p>}><AssessmentPage /></Suspense>}/>
          <Route path="token-usage" element={<TokenUsagePage />}/>
          <Route path="users" element={<UsersPage />}/>
          <Route path="documents" element={<DocUploadPage />}/>
          <Route path="invitation-codes" element={<InvitationCodesPage />}/>
        </Route>
        <Route path="*" element={<Navigate to="/" replace/>}/>
      </Routes>
    </BrowserRouter>);
}
