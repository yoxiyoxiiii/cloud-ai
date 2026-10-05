/** 临时静态服务：供视觉模型经 HTTP 读取 e2e 截图（用完即关） */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'artifacts')
const PORT = 6173

const server = http.createServer((req, res) => {
  const name = path.basename(decodeURIComponent(req.url.split('?')[0]))
  const file = path.join(ROOT, name)
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404).end('not found')
    return
  }
  res.writeHead(200, { 'Content-Type': name.endsWith('.png') ? 'image/png' : 'text/plain; charset=utf-8' })
  fs.createReadStream(file).pipe(res)
})
server.listen(PORT, '0.0.0.0', () => console.log(`serving ${ROOT} on http://localhost:${PORT}`))
