import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import router from './router'

// Element Plus 按需引入（前端规范）：模板组件由 unplugin-vue-components 自动解析，
// 不在此全量 app.use(ElementPlus)；函数式 API（ElMessage/ElMessageBox）在使用处显式 import，
// 其样式无法被模板解析器捕获，需在此单独引入（仅 message 与 message-box 两处）：
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
