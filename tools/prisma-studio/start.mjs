import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import http from 'node:http'

const required = ['STUDIO_DB_HOST', 'STUDIO_DB_NAME', 'STUDIO_DB_USER', 'STUDIO_DB_PASSWORD']
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing ${name}`)
}

// Encode credentials independently so passwords containing @, :, / or # work.
const user = encodeURIComponent(process.env.STUDIO_DB_USER)
const password = encodeURIComponent(process.env.STUDIO_DB_PASSWORD)
const database = encodeURIComponent(process.env.STUDIO_DB_NAME)
const url = `postgresql://${user}:${password}@${process.env.STUDIO_DB_HOST}:${process.env.STUDIO_DB_PORT || '5432'}/${database}`
const cli = fileURLToPath(new URL('./node_modules/prisma/build/index.js', import.meta.url))
// Studio 7 binds to loopback. Forward HTTP inside the container so Docker can
// publish it, preserving Studio's same-origin checks for the public local port.
const publicPort = process.env.STUDIO_PUBLIC_PORT || '5555'
const allowedOrigins = new Set([`http://localhost:${publicPort}`, `http://127.0.0.1:${publicPort}`])
const server = http.createServer((req, res) => {
  const headers = { ...req.headers, host: '127.0.0.1:5557' }
  if (allowedOrigins.has(headers.origin)) headers.origin = 'http://127.0.0.1:5557'
  const upstream = http.request({ hostname: '127.0.0.1', port: 5557, path: req.url, method: req.method, headers }, response => {
    res.writeHead(response.statusCode, response.headers)
    response.pipe(res)
  })
  upstream.on('error', () => {
    if (!res.headersSent) res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('Prisma Studio is starting. Please retry shortly.')
  })
  req.pipe(upstream)
})
server.on('error', () => {
  console.error('Could not open Studio HTTP port')
  child.kill('SIGTERM')
  process.exit(1)
})
const child = spawn(process.execPath, [cli, 'studio', '--url', url, '--port', '5557', '--browser', 'none'], {
  stdio: 'inherit',
})
server.listen(5555, '0.0.0.0', () => {
  console.log(`Database UI: http://localhost:${publicPort} (proxy to internal Studio port 5557)`)
})
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close()
    server.closeAllConnections()
    child.kill(signal)
    // The CLI can retain database connections after receiving SIGTERM.
    // Bound shutdown so a stopped Studio never keeps the published port open.
    setTimeout(() => {
      child.kill('SIGKILL')
      process.exit(0)
    }, 3000).unref()
  })
}
child.on('error', () => {
  console.error('Could not start Prisma Studio')
  process.exit(1)
})
child.on('exit', (code, signal) => {
  server.close()
  process.exit(code ?? (signal ? 1 : 0))
})
