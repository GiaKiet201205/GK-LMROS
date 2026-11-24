import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const portal = ['driver', 'customer'].includes(mode) ? mode : env.VITE_PORTAL || 'dispatcher'
  if (!['driver', 'dispatcher', 'customer'].includes(portal)) throw new Error('VITE_PORTAL must be driver, customer or dispatcher')
  return {
    build: { outDir: `dist/${portal}` },
    plugins: [react(), {
      name: 'portal-metadata',
      transformIndexHtml: html => html
        .replace('<title>GK</title>', `<title>GK ${portal === 'driver' ? 'Tài xế' : portal === 'customer' ? 'Gửi hàng' : 'Quản lý'}</title>`)
        .replace('href="/manifest.json"', portal === 'driver' ? 'href="/manifest.json"' : portal === 'customer' ? 'href="/manifest-customer.json"' : 'href="/manifest-manager.json"'),
    }],
    define: { 'import.meta.env.VITE_PORTAL': JSON.stringify(portal) },
    server: {
      port: portal === 'driver' ? 3001 : portal === 'customer' ? 3002 : 3000,
      proxy: { '/api': { target: 'http://localhost:8080', headers: { 'X-GK-Portal': portal } } },
    },
  }
})
