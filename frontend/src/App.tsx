import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import './App.css'
import { getRole, isLoggedIn } from './api/auth'
import ChatPage from './pages/ChatPage'
import LoginPage from './pages/LoginPage'
import AdminLayout from './pages/admin/AdminLayout'
import TokenUsagePage from './pages/admin/TokenUsagePage'
import UsersPage from './pages/admin/UsersPage'
import DocUploadPage from './pages/admin/DocUploadPage'

/**
 * 受保护路由：未登录 → /login
 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  return isLoggedIn() ? <>{children}</> : <Navigate to="/login" replace />
}

/**
 * 管理员路由：非 admin → 跳聊天页
 */
function RequireAdmin({ children }: { children: React.ReactNode }) {
  if (!isLoggedIn()) return <Navigate to="/login" replace />
  if (getRole() !== 'admin') return <Navigate to="/" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <ChatPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin"
          element={
            <RequireAdmin>
              <AdminLayout />
            </RequireAdmin>
          }
        >
          <Route index element={<Navigate to="token-usage" replace />} />
          <Route path="token-usage" element={<TokenUsagePage />} />
          <Route path="users" element={<UsersPage />} />
          <Route path="documents" element={<DocUploadPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
