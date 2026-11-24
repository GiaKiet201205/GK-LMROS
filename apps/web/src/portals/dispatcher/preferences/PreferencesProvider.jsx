import { createContext, useContext, useEffect, useState } from 'react'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { api, useApi } from '../../../shared/api/client.js'
import { normalizePreferences, resolveTheme } from './preferences.js'

const Context = createContext(null)
export const usePreferences = () => useContext(Context)
export default function PreferencesProvider({ children }) {
  const { user } = useAuth(), key = `gk_dispatcher_preferences_${user.id}`
  const [preferences, setPreferences] = useState(() => { try { return normalizePreferences(JSON.parse(localStorage.getItem(key))) } catch { return normalizePreferences(null) } })
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  const [busy, setBusy] = useState(false), [saveError, setSaveError] = useState(''), [saved, setSaved] = useState(false)
  const { data, error, reload } = useApi('/api/preferences')
  function remember(value) { try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* Server remains the source of truth. */ } }
  useEffect(() => { if (data) { const value = normalizePreferences(data); setPreferences(value); remember(value) } }, [data])
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)'), update = e => setSystemDark(e.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  async function update(values) {
    if (busy || !data) return
    const previous = preferences, next = normalizePreferences({ ...preferences, ...values })
    setPreferences(next); setBusy(true); setSaved(false); setSaveError('')
    try { const value = normalizePreferences(await api('/api/preferences', { method: 'PUT', body: next })); setPreferences(value); remember(value); setSaved(true) }
    catch (e) { setPreferences(previous); setSaveError(e.message) } finally { setBusy(false) }
  }
  return <Context.Provider value={{ preferences, theme: resolveTheme(preferences.theme, systemDark), ready: Boolean(data), busy, error: saveError || error?.message, saved, update, reload: () => { setSaveError(''); reload() } }}>{children}</Context.Provider>
}
