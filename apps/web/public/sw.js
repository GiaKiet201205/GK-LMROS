// Service worker tối thiểu: cache khung giao diện để app tài xế mở được khi mất mạng. API luôn đi qua mạng.
const CACHE = 'gk-shell-v4'
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/', '/login', '/icon.svg'])))
  self.skipWaiting()
})
self.addEventListener('activate', (e) => e.waitUntil((async () => {
  for (const name of await caches.keys()) {
    if ((name.startsWith('vietroute-shell-') || name.startsWith('gk-shell-')) && name !== CACHE) await caches.delete(name)
  }
  await self.clients.claim()
})()))
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url)
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/') || url.origin !== location.origin) return
  e.respondWith(
    fetch(e.request)
      .then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('/')))
  )
})
