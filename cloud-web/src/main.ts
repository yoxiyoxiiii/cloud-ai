import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import router from './router'
import { getAppPrefs } from './utils/storage'
import { vPerms } from './directives/perms'

// Element Plus 按需引入（前端规范）：模板组件由 unplugin-vue-components 自动解析，
// 不在此全量 app.use(ElementPlus)；函数式 API（ElMessage/ElMessageBox）在使用处显式 import，
// 其样式无法被模板解析器捕获，需在此单独引入（仅 message 与 message-box 两处）：
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

// 深色主题变量（升级设计 D2）：官方暗色变量文件，全文仅 html.dark 下的 --el-* 自定义属性
// 覆盖与 color-scheme 声明（本机 2.14.7 实测 2946 字节，无组件样式规则、无 JS）——
// 属按需体系内的官方暗色通道，非 dist/index.css 全量组件样式，是按需红线的唯一例外。
import 'element-plus/theme-chalk/dark/css-vars.css'

// 深色 FOUC 防护：storage 读取是同步的，挂载前先落 html.dark 类，无闪烁窗口；
// 此后主题切换由 app store 的 toggleTheme 维护类与落盘一致性（升级设计 §3.2）
if (getAppPrefs().theme === 'dark') {
  document.documentElement.classList.add('dark')
}

const app = createApp(App)

app.use(createPinia())
app.use(router)

// v-perms 按钮级权限指令全局注册（契约 perms-api §4）：不命中快照即移除 DOM，
// 任意页面模板直接 v-perms="'system:user:add'"（main.ts 注册点，避免每页局部注册样板）
app.directive('perms', vPerms)

app.mount('#app')
