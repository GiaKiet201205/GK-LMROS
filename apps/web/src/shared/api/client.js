import { useEffect, useState, useCallback, useRef } from 'react'

export async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-GK': '1', ...opts.headers },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  if (!res.ok) {
    if (res.status === 401 && !path.endsWith('/auth/login') && !path.endsWith('/auth/register')) window.dispatchEvent(new Event('gk-auth-expired'))
    const payload = await res.json().catch(() => ({}))
    const error = new Error(payload.message || `${res.status} ${res.statusText}`)
    error.fields = payload.fields || {}
    error.status = res.status
    throw error
  }
  return res.status === 204 ? null : res.json()
}

/** Tải dữ liệu từ API, tự làm mới theo chu kỳ (ms) nếu có. */
export function useApi(path, intervalMs = 0) {
  const requestId = useRef(0)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const load = useCallback(() => {
    if (!path) return
    const id = ++requestId.current
    api(path).then((d) => { if (id === requestId.current) { setData(d); setError(null) } }).catch(e => { if (id === requestId.current) setError(e) })
  }, [path])
  useEffect(() => {
    setData(null)
    load()
    window.addEventListener('gk-fleet-updated',load)
    const t = intervalMs ? setInterval(load, intervalMs) : null
    return () => { clearInterval(t);window.removeEventListener('gk-fleet-updated',load);requestId.current++ }
  }, [load, intervalMs])
  return { data, error, reload: load }
}

export const nf = (n, d = 0) => (n == null ? '–' : Number(n).toLocaleString('vi-VN', { maximumFractionDigits: d }))
