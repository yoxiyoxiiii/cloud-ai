# AI 对话页视觉分区（会话列表 ↔ 消息区）实施计划（2026-10-15，纯前端 B 级）

> **For agentic workers:** 本计划按任务顺序执行，步骤用 checkbox 跟踪。
> 需求：AI 对话界面对话列表与对话区域要有视觉上的区分。零 API / 零契约 / 零后端改动。

**Goal**: /rag/chat 页左右两区从「同底色连体」改为「灰底列表面板 + 白底消息卡片」的双面板分区形态。

**Architecture**: 仅改 `cloud-web/src/views/rag/chat/index.vue` 的 `<template>`（新增一个列表标题节点）与 `<style scoped>`（面板化样式）；**全部既有类名保留**（e2e 选择器零影响）；颜色沿用页面既有 EP 字面色习惯（#f5f7fa / #ecf5ff / #409eff 等），不引新依赖。

**Tech Stack**: Vue3 scoped CSS（无组件库新增——不新增任何 EP 组件）。

---

## 现状与目标形态

现状：`.rag-chat` flex 两栏直接铺在布局底色上，仅 12px gap——两区无边界、无底色差，视觉上连成一片。
目标：

- 左 `.sessions`：**灰底面板**（`#f5f7fa` 填充 + 圆角 + 内边距），顶部加小节标题「对话列表」；条目 hover/active 色阶改配灰底（现状 #ecf5ff 活动色在灰底上对比不足）。
- 右 `.main`：**白底卡片**（`#fff` + `1px #e4e7ed` 边框 + 圆角），消息列表与输入栏收进卡片；输入栏上缘加一条浅分隔线，形成「消息区滚动 / 输入区贴底」的层次。

---

### Task 1: 样式面板化（index.vue）

**Files:**
- Modify: `cloud-web/src/views/rag/chat/index.vue`（template 加标题节点 + style 重写以下选择器）

- [ ] **Step 1.1: template 左栏顶部加标题**（`aside.sessions` 内、新建按钮之前）：

```html
<aside class="sessions">
  <div class="sessions-title">对话列表</div>
  <el-button type="primary" class="new-btn" @click="newSession">新建对话</el-button>
  ...
```

- [ ] **Step 1.2: style 替换/新增以下规则**（其余规则不动）：

```css
/* 左：会话列表——灰底面板，与右侧白底消息卡片形成视觉分区 */
.sessions {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
  overflow-y: auto;
}
.sessions-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  padding: 0 0 8px 2px;
}
/* 灰底上的色阶：hover 深一档，活动色用 primary 浅底（#ecf5ff 对比不足改 #d9ecff） */
.session-item:hover {
  background: #ebedf0;
}
.session-item.active {
  background: #d9ecff;
  color: #409eff;
}
/* 右：消息区——白底卡片（边框 + 圆角），与灰底列表面板分区 */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
}
/* 输入区与消息区的浅分隔线（层次：消息滚动区 / 输入贴底） */
.input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}
```

同时删除被上表覆盖的旧规则里的 `padding: 12px`（`.msg-list` 原有——卡片已带内边距，避免双重）与 `.input-bar` 原 `padding-top: 8px`（被新值取代）。`.new-btn { margin-bottom: 8px }` 保留。

- [ ] **Step 1.3: 自查**——`git diff` 确认：零类名删除/改名；`defineOptions({ name: 'RagChat' })` 与全部逻辑代码（script 段）零改动。

### Task 2: 构建验证

- [ ] `cd cloud-web && npm run build` **连续两次**全绿（二次起类型更严——按需引入已知坑）。

### Task 3: e2e 回归与视觉核对（全栈经 `scripts/dev-services.sh` 管理）

- [ ] **Step 3.1**: `./scripts/dev-services.sh start`（脚本已实测：6 服务 + 网关 ping 冒烟）。
- [ ] **Step 3.2**: `cd cloud-e2e && npm run e2e:aichat` —— 判定只认 `grep -c '[FAIL]'`（harness 不设退出码）。选择器全类名锚定，本改动理论上零影响，红了先查类名是否被误删。
- [ ] **Step 3.3**: 全量回归 `npm run e2e` —— 0 FAIL。
- [ ] **Step 3.4**: 截图 ≥2 张（对话页空态 + 新建会话发过一轮消息的带消息态）→ 上传平台取远程 URL → analyze_image 比对（左灰底面板含「对话列表」标题与新建按钮；右白底卡片含气泡与输入栏分隔线；两区边界清晰可辨）→ **核对完删除截图**（artifacts 不进 git）。
- [ ] **Step 3.5**: `./scripts/dev-services.sh stop`（复检全 DOWN）。

---

## 验收标准

1. build ×2 绿；aichat 与全量 e2e 0 FAIL。
2. 视觉：左右两区一眼可分（灰底面板 vs 白底边框卡片）；活动会话高亮在灰底上清晰。
3. script 段零改动、类名零增删改（e2e 兼容）；截图验收后已删；服务零残留。

## 移交备忘

- 无新欠账。若后续要做会话持久化/多轮历史（现为前端本地态），见上轮 AI 对话设计文档的迭代方向章节。
