import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './style.css'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// Element Plus 默认 locale 为英文（分页 Total、MessageBox OK/Cancel 等），中文管理台需显式配置
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
