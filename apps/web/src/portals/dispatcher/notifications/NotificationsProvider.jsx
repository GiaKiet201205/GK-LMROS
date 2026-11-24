import { createContext, useContext, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Bell, X } from 'lucide-react'
import { api, useApi } from '../../../shared/api/client.js'
import { usePreferences } from '../preferences/PreferencesProvider.jsx'

const Context = createContext(null)
export const useNotifications = () => useContext(Context)
export const notificationTime = value => new Date(value).toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' })
export default function NotificationsProvider({ children }) {
  const feed = useApi('/api/notifications?limit=8', 5000), { preferences } = usePreferences()
  const previous = useRef(null), [toast, setToast] = useState(null), [actionError, setActionError] = useState('')
  useEffect(() => {
    if (!feed.data) return
    const newest = feed.data.items[0]
    if (newest && previous.current !== null && newest.id !== previous.current && !newest.read_at && preferences.notificationToasts) setToast(newest)
    previous.current = newest?.id ?? 0
  }, [feed.data, preferences.notificationToasts])
  useEffect(() => { if (!preferences.notificationToasts) setToast(null) }, [preferences.notificationToasts])
  async function read(id) {
    setActionError('')
    try { await api('/api/notifications/read', { method: 'POST', body: id ? { id } : { throughId: feed.data?.latestId } }); feed.reload() }
    catch (e) { setActionError(e.message); throw e }
  }
  return <Context.Provider value={{ ...feed, read, actionError, toast, dismissToast: () => setToast(null) }}>{children}</Context.Provider>
}
export function WorkspaceToast() {
  const { toast, read, dismissToast } = useNotifications()
  return toast ? <aside className="workspace-toast" role="status"><Bell size={20} /><Link to={toast.href} onClick={() => { read(toast.id).catch(() => {}); dismissToast() }}><strong>{toast.title}</strong><span>{toast.message}</span></Link><button className="icon-button" aria-label="Đóng thông báo mới" onClick={dismissToast}><X size={17} /></button></aside> : null
}
