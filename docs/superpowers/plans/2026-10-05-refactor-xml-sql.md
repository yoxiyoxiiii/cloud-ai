# 重构：SQL 下沉 Mapper XML 计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** cloud-system 全部 SQL 操作改用 Mapper XML 手写 SQL（`src/main/resources/mapper/*.xml`）；Service 层消除 `LambdaQueryWrapper`；确需 MP 能力（分页）的以 `Page` 参数方法留在 Mapper 接口。**行为保持不变。**

**两个必须显式处理的语义**（手写 SQL 后 MP 自动机制失效）：
1. `@TableLogic` 不再自动过滤——所有主表 WHERE 显式 `AND deleted = 0`，删除为 `UPDATE SET deleted=1`；纯关系表（sys_user_role/sys_role_menu）无 deleted，物理 DELETE。
2. `MetaObjectHandler` 自动填充不再触发——INSERT/UPDATE 显式传 `create_by/create_time/update_by/update_time`（操作人来自新工具 `SecurityUtils.currentAccount()`，时间 `LocalDateTime.now()`）。

**保持不变的 MP 语义**：分页插件对 XML 方法生效（`IPage<T> method(Page<T> page, ...)`，XML 不写 LIMIT）；`DuplicateKeyException` 兜底照旧；实体 `@JsonProperty(WRITE_ONLY)`/`@ToString.Exclude` 照旧。mybatis-plus starter 默认扫描 `classpath*:/mapper/**/*.xml`，无需配置。

**Task 1: SecurityUtils + Mapper XML 化 + Service 改造**

1. 新建 `cloud-common-security-starter/.../util/SecurityUtils.java`：

```java
package com.cloudai.common.security.util;

import com.cloudai.common.core.domain.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录上下文工具（手写 SQL 场景替代 MetaObjectHandler 自动填充取操作人）
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 当前登录账号；匿名/无上下文返回 null */
    public static String currentAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getAccount();
        }
        return null;
    }
}
```

2. 五个 Mapper 接口重写（去 BaseMapper 继承，方法与 XML 一一对应）。命名规范：查询 `selectXxx`、写入 `insertXxx/updateXxx/deleteXxx`。分页方法签名 `IPage<T> selectXxxPage(Page<T> page)`。INSERT 用 `@Options(useGeneratedKeys = true, keyProperty = "id")`（或 XML 属性）。批量插入用 `<foreach>`，方法参数 `@Param("list")`，service 层空列表跳过调用。

3. 五个 XML（`cloud-base/cloud-system/src/main/resources/mapper/`）。**SQL 语义对照现实现逐条等价**，要点：
   - 主表全部查询 `deleted = 0`；删除 `UPDATE ... SET deleted=1, update_by=#{...}, update_time=#{...} WHERE id=#{id} AND deleted=0`
   - `SysUserMapper.xml`：selectByAccount（LIMIT 1）/selectById/selectPage(ORDER BY id DESC)/countByAccount/insert/update(nickname,status,update_by,update_time)/updatePassword/deleteById
   - `SysRoleMapper.xml`：selectPage/selectEnabledAll(status=0 ORDER BY id)/selectById/countByRoleKey（`role_key=#{roleKey}` + `AND id != #{excludeId}` 当 excludeId 非 null——用 `<if>`）/insert/update/deleteById
   - `SysMenuMapper.xml`：selectAll(ORDER BY sort, id)/selectById/countByParentId/insert/update/deleteById
   - `SysUserRoleMapper.xml`：selectRoleIdsByUserId/insertBatch(foreach)/deleteByUserId/deleteByRoleId
   - `SysRoleMenuMapper.xml`：selectMenuIdsByRoleId/insertBatch/insert单条(种子)/deleteByRoleId/deleteByMenuId
   - `SysUserLinkageService` 的 5 连查收敛为 2 个 XML 查询（JOIN 红利）：
     `selectUserByAccount`（同 selectByAccount）+ `selectPermsByAccount`：
     ```sql
     SELECT DISTINCT m.perms FROM sys_user u
       JOIN sys_user_role ur ON ur.user_id = u.id
       JOIN sys_role r ON r.id = ur.role_id AND r.status = 0 AND r.deleted = 0
       JOIN sys_role_menu rm ON rm.role_id = r.id
       JOIN sys_menu m ON m.id = rm.menu_id AND m.status = 0 AND m.deleted = 0
      WHERE u.account = #{account} AND u.deleted = 0 AND m.perms != ''
     ```
4. 四个 Service 改造：删 Wrapper，调用 mapper 自定义方法；写操作构造审计参数（`String operator = SecurityUtils.currentAccount(); LocalDateTime now = ...`——insert 传 create/update 四值，update 传 update 两值；delete 传 update 两值）。事务注解/业务校验/错误码/查重+DuplicateKey 兜底**全部保持**。`assignRoles/assignMenus`：先 delete 后 insertBatch（distinct 去重保留）。
5. 实体/Mapper 相关测试调整：`SysMenuManageServiceTest`/`SysRoleManageServiceTest` 的 mock 方法名同步（如 selectById→selectMenuById 按最终命名）；断言语义不变。
6. 验证：`mvn -f cloud-base/pom.xml clean install` BUILD SUCCESS 全测试绿；`grep -r LambdaQueryWrapper cloud-base/cloud-system/src/main` 零命中。

**Task 2: 端到端回归（行为保持证明）**

起 system+sso+gateway → 登录 → 分页 200（total "1"）/ 建用户（验 create_by=admin）/ inner 聚合 17 权限 / 角色查重 3003 / 菜单树两根 / 删用户后分页还原 → 清理停服。与重构前行为逐项一致。

**执行完成后**：合并 main；扩展清单补记"XML SQL 的 deleted=0 与审计字段由手写维护，新增表需遵守"。
