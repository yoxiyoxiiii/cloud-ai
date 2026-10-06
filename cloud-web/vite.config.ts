import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板中 <el-xxx> 构建期自动解析为组件级 import + 样式，
    // 严禁 app.use(ElementPlus) 全量注册与 element-plus/dist/index.css 全量样式（前端规范）
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
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
