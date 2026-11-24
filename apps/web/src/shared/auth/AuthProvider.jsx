import { createContext, useContext, useEffect, useState } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { api } from '../api/client.js'

import { AUTH_BASE } from '../config/portal.js'

const AuthContext = createContext(null)
export const useAuth = () => useContext(AuthContext)
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const refresh = () => {
    setLoading(true); setError(null)
    api(`${AUTH_BASE}/me`).then(setUser).catch(e => { setUser(null); if (e.status !== 401) setError(e.status===404?'Dịch vụ đăng nhập đang được chuyển sai địa chỉ. Cần khởi động lại gateway.':e.message) }).finally(() => setLoading(false))
  }
  useEffect(() => {
    refresh()
    const expired = () => setUser(null)
    window.addEventListener('gk-auth-expired', expired)
    return () => window.removeEventListener('gk-auth-expired', expired)
  }, [])
  const login = async (username, password) => {
    const u = await api(`${AUTH_BASE}/login`, { method: 'POST', body: { username, password } })
    setUser(u); return u
  }
  const logout = async () => {
    await api(`${AUTH_BASE}/logout`, { method: 'POST' }); setUser(null)
  }
  const register = async body => { const u = await api(`${AUTH_BASE}/register`, { method: 'POST', body }); setUser(u); return u }
  const googleLogin = async body => {
    const result = await api(`${AUTH_BASE}/google`, { method: 'POST', body })
    if (!result.requiresProfile) setUser(result)
    return result
  }
  return <AuthContext.Provider value={{ user, loading, error, refresh, login, logout, register, googleLogin }}>{children}</AuthContext.Provider>
}
export function RequireAuth({ role }) {
  const { user, loading, error, refresh } = useAuth()
  const location = useLocation()
  if (loading) return <div className="auth-page">Đang kiểm tra đăng nhập…</div>
  if (error) return <div className="auth-page"><div className="card"><p role="alert">Không kết nối được máy chủ: {error}</p><button className="btn" onClick={refresh}>Thử lại</button></div></div>
  if (!user) return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  if (user.role !== role) return <div className="auth-page" role="alert">Tài khoản không có quyền truy cập cổng này.</div>
  return <Outlet />
}
