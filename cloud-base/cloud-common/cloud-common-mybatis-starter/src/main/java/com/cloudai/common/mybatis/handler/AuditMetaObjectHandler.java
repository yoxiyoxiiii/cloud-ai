package com.cloudai.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.cloudai.common.core.domain.LoginUser;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：无对应属性的实体自动跳过；时间语义为服务器时间总是生效（覆盖调用方已设值）；
 * 操作人取登录上下文（LoginUser.account），匿名场景留空。
 * 匿名/异步上下文的 UPDATE 保留原 updateBy（不清空、不归因错误的人）；update(null, wrapper) 纯条件更新无实体可填充，不维护审计字段。
 * 仅对 MP BaseMapper CRUD 生效；手写 SQL 的 Service 用 SecurityUtils 显式传参。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();
        if (metaObject.hasSetter("createTime")) {
            setFieldValByName("createTime", now, metaObject);
        }
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", now, metaObject);
        }
        if (metaObject.hasSetter("createBy")) {
            setFieldValByName("createBy", operator, metaObject);
        }
        if (metaObject.hasSetter("updateBy")) {
            setFieldValByName("updateBy", operator, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
        if (metaObject.hasSetter("updateBy")) {
            setFieldValByName("updateBy", currentOperator(), metaObject);
        }
    }

    private String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getAccount();
        }
        return null;
    }
}
