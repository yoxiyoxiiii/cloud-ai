/**
 * 系统名称单一语义源（升级设计 D1）
 * 消费方：Sidebar 品牌区 / 登录页标题 / router.afterEach 的 document.title。
 * 注意：index.html 的 <title> 无法 import 模块，是该常量的第二处副本
 * （index.html 内有注释指回本文件，改文案时两处同步）。
 */
export const APP_TITLE = 'CloudAI 企业基座'
