import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:18080', // cloud-gateway
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''), // /api/sso/x → 网关 /sso/x
      },
    },
  },
})
