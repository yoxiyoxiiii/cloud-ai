package com.cloudai.system.api.dataperm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限列载体 VO 白名单标记（设计 D56）：标记该 VO 的响应由 {@link DataPermColumnAdvice}
 * 树遍历统一应用列动作（上下文缺失即 fail-closed 3042 拒绝响应）。
 *
 * <p>无参数（拍板④）：资源标识唯一真相源是 provider 注册表 register/registerRemote 行，
 * 注解不做第二声明点；「标注解」义务由 {@link DataPermColumns#assertDeclared} 统一断言
 * （未标 → 启动失败，本地 register / 消费方 runner 两路单点全覆盖）。</p>
 *
 * <p>detail 包装类（如 SysLeaveDetailVo/ApprovalDetailVo）与步骤 VO 不标——advice 树遍历
 * 经 com.cloudai 字段下钻自然到达（D55，与翻译「只下钻标注类」的差异理由见设计）。</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataPermVo {
}
