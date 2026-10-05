# cloud-web

企业应用基座平台前端（Vue 3.5 + TypeScript + Vite 6 + Element Plus 2 全量引入 + Pinia + Vue Router + Axios）。

- 开发：`npm install && npm run dev`（5173，`/api` 经 Vite 代理转发网关 `http://localhost:18080`，见 `vite.config.ts`）
- 构建：`npm run build`（vue-tsc 类型检查 + 产物 `dist/`）；`.env.production` 部署形态实现时验证
- 接口契约（唯一对齐物）：`../docs/superpowers/contracts/2026-10-05-pilot-auth-user-api.md`
