---
name: backend-spec
description: 后端开发规范基准（cloud-system/cloud-bpmn/新增 DB 服务通用）——DDL 与索引设计、实体、Mapper 接口、mapper XML、请求 DTO、VO+Convert、Service、Controller 全套模板 + 事务口径 + 检查清单。凡后端任务（新增表/新增接口/新增 CRUD/迭代功能改 SQL/加字段/新增 /inner 端点）均应触发本技能，不止新增 CRUD。
---

# 后端开发规范（全部后端服务通用）

本技能 = CLAUDE.md「编码规范」的可执行落地：CRUD 全套模板 + **索引设计** + **事务口径** + 检查清单。**不止新增 CRUD**——迭代功能改查询/加字段/动 SQL 同样先过本技能对应章节（新增查询路径必审索引、事务口径必对、代码风格随模板）。按此技能创建的代码自动满足 CLAUDE.md「编码规范」与 ArchitectureGuardTest；**与 CLAUDE.md 冲突时以 CLAUDE.md 为准**。模板与 cloud-system 现网代码同风格：Mapper/Service 方法名用动词集且**省实体名前缀**（接口名已限定实体，如 `SysUserMapper.findById` 而非 `findUserById`）。以新增实体 `XxxYyy`（表 `xxx_yyy`，服务短名 `<svc>`）为例。

## 步骤 0：建表 SQL（scripts/sql/ 追加）

```sql
CREATE TABLE xxx_yyy (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(30) NOT NULL COMMENT '名称',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30) DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
    -- 普通索引按查询清单补：KEY idx_<列> (<列>)——见下方「索引设计」，必配不可省
) ENGINE = InnoDB COMMENT = 'Xxx说明';
```

要点：审计四列 + `deleted TINYINT NOT NULL DEFAULT 0`（防 NULL 行被 @TableLogic 过滤隐身）；唯一键 `uk_xxx`；**每列必须有 COMMENT，含审计列（创建人/创建时间/更新人/更新时间，守护测试机械检查）**；纯关系表（无业务生命周期的关联）**不要** deleted/审计列，但列同样要 COMMENT。

### 索引设计（DDL 必配，迭代新增查询必审）

每张表 DDL 必须显式完成索引设计——**依据是本表的 mapper 查询清单**（WHERE / JOIN / ORDER BY 列），每个索引注明命中的查询与取舍。**迭代功能为既有表新增查询路径时同样必审**：查询列是否被既有索引覆盖（EXPLAIN 抽查确认 key 命中），缺失则出增量 DDL（`ALTER TABLE ... ADD INDEX`，进 `scripts/sql/<日期>-*.sql` 增量脚本）——不许"查询上线、索引欠账"。

- **唯一键 `uk_<语义>`**：业务唯一性 + 查重路径二合一（如 `uk_dict_key`、`uk_type_value(dict_type_id, value)`）——查重 COUNT 与等值查询都走它；复合唯一键注意**最左前缀复用**（`uk_type_value` 的左前缀已覆盖"按类型查子表"，无需再建 `idx_dict_type_id`）
- **普通索引 `idx_<列/语义>`**：外键式关联列必建（子表按父查询/删除前 count 校验）；高频 WHERE 等值列；大表分页的 ORDER BY 列（小表 filesort 可容忍，取舍写明即可）
- **不建**：`deleted` 单列（基数只有 0/1，选择性极差，全表都带此条件但不值得单建）；写多读少的表宁缺毋滥（每个索引都是写放大）
- 命名与现网一致：`uk_` / `idx_` 前缀小写下划线；索引与查询的对应关系写在 DDL 注释或方案文档

权限种子（管理端点需要）：权限体系集中在 cloud-system 的 sys_menu——追加 INSERT（perms 形如 `<svc>:xxx:list/add/edit/remove`，领域动作可加 `resetPwd`/`assignRole` 等）+ `INSERT INTO sys_role_menu ... SELECT 1, id FROM sys_menu` 增量映射 admin 角色。

## 步骤 1：实体（entity/XxxYyy.java，状态枚举内嵌）

```java
package com.cloudai.<service>.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("xxx_yyy")
public class XxxYyy extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 0正常 1停用 */
    private Integer status;

    /** 状态枚举内嵌实体，命名以 Enum 为后缀（字段保持 Integer 映射；Java 侧禁魔法数，SQL 字面量除外） */
    public enum StatusEnum {
        NORMAL(0), DISABLED(1);

        private final int code;

        StatusEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static StatusEnum of(int code) {
            for (StatusEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知状态: " + code);
        }
    }
}
```

注解仅为元数据（手写 SQL 不依赖 @TableLogic 自动语义）。敏感字段（密码等）加 `@ToString.Exclude` + `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`；纯关系表实体**不继承** BaseEntity。

## 步骤 2：Mapper 接口（mapper/XxxYyyMapper.java）

```java
package com.cloudai.<service>.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.<service>.entity.XxxYyy;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Xxx 表 SQL（XML：mapper/XxxYyyMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0，删除为 UPDATE deleted=1；
 * INSERT/UPDATE 的审计列由 Service 显式传参（更新走实体 updateBy/updateTime 字段）。
 */
public interface XxxYyyMapper {

    XxxYyy findById(@Param("id") Long id);

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加 */
    IPage<XxxYyy> pageList(Page<XxxYyy> page);

    /** 唯一键查重（编辑时传 excludeId 排除自身，新增传 null） */
    Long countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    int save(XxxYyy xxx);

    /** 动态更新（仅非空列；审计两值随实体 updateBy/updateTime 传入） */
    int update(XxxYyy xxx);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
```

规则：方法名用动词集（find/save/update/pageList/list/delete/count），**不带实体名**；禁止 `extends BaseMapper`；聚合查询（跨表 JOIN 取权限等）放相关主表 Mapper（参考 `SysUserMapper.listPermsByAccount` 一次成型）；领域更新（如改密码）独立方法（参考 `updatePassword`）；主键回填靠 XML `useGeneratedKeys`，不用接口 @Options。

## 步骤 3：mapper XML（resources/mapper/XxxYyyMapper.xml）

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.cloudai.<service>.mapper.XxxYyyMapper">

    <!-- 查询显式 deleted = 0，删除为 UPDATE deleted=1；审计列由 Service 显式传参 -->
    <sql id="allColumns">
        id, name, status, create_by, create_time, update_by, update_time, deleted
    </sql>

    <select id="findById" resultType="com.cloudai.<service>.entity.XxxYyy">
        SELECT <include refid="allColumns"/>
          FROM xxx_yyy
         WHERE id = #{id} AND deleted = 0
    </select>

    <!-- 分页由 PaginationInnerInterceptor 追加 LIMIT 与 COUNT，SQL 不写 LIMIT -->
    <select id="pageList" resultType="com.cloudai.<service>.entity.XxxYyy">
        SELECT <include refid="allColumns"/>
          FROM xxx_yyy
         WHERE deleted = 0
         ORDER BY id DESC
    </select>

    <select id="countByName" resultType="long">
        SELECT COUNT(*)
          FROM xxx_yyy
         WHERE name = #{name} AND deleted = 0
            <if test="excludeId != null">
                AND id != #{excludeId}
            </if>
    </select>

    <insert id="save" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO xxx_yyy (name, status, create_by, create_time, update_by, update_time)
        VALUES (#{name}, #{status}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime})
    </insert>

    <!-- 动态列等价 NOT_NULL 字段策略：null 列不改库值；<if> 标签体必须换行 -->
    <update id="update">
        UPDATE xxx_yyy
        <set>
            <if test="name != null">
                name = #{name},
            </if>
            <if test="status != null">
                status = #{status},
            </if>
            <if test="updateBy != null">
                update_by = #{updateBy},
            </if>
            update_time = #{updateTime},
        </set>
        WHERE id = #{id} AND deleted = 0
    </update>

    <!-- update_by 匿名保留原值：<if> 判空，逗号前置 -->
    <update id="deleteById">
        UPDATE xxx_yyy
           SET deleted = 1,
               update_time = #{updateTime}
               <if test="updateBy != null">
                   , update_by = #{updateBy}
               </if>
         WHERE id = #{id} AND deleted = 0
    </update>
</mapper>
```

规则：主表每条 select/update 语句必含 `deleted`（守护测试按表名词边界检查）；只用 `#{}` 禁 `${}`；分页不写 LIMIT，`LIMIT 1` 仅唯一键防御；**`<if>` 标签体必须换行**（单行 `<if>` 构建即红）；update_by 用 `<if>` 判空保留原值（匿名场景语义）；纯关系表物理 DELETE + `<foreach>` 批量插入（Service 层空列表跳过）。

## 步骤 4：请求 DTO 与 VO（dto/ + vo/ + convert/）

**入参 DTO**（Controller/Service 入参一律对象，禁 Map；写操作不裸传实体——新增/修改语义不同字段必填性不同）：

```java
package com.cloudai.<service>.dto;

import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/** Xxx 新增/修改入参（新增必填 name；修改必填 id） */
@Data
public class XxxSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填；新增忽略 */
    private Long id;
    /** 新增必填；修改按需 */
    private String name;
    /** 0正常 1停用 */
    private Integer status;
}
```

领域动作（重置密码/分配角色等）建独立 DTO（参考 `ResetPasswordRequest`/`UserRoleRequest`）；DTO 内敏感字段加 `@ToString.Exclude`。

**出参 VO**（Controller 一律 VO，禁 DB 实体直出）：

```java
package com.cloudai.<service>.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Xxx 出参 VO（页面所需字段子集；敏感字段与 deleted 不进 VO） */
@Data
public class XxxYyyVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    /** 0正常 1停用 */
    private Integer status;
    private String createBy;
    private LocalDateTime createTime;
}
```

**Convert**（转换统一在 convert 包，原生 setter 逐字段，禁 BeanUtils/mapstruct 等三方拷贝）：

```java
package com.cloudai.<service>.convert;

import com.cloudai.<service>.entity.XxxYyy;
import com.cloudai.<service>.vo.XxxYyyVo;

/** 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具） */
public final class XxxYyyConvert {

    private XxxYyyConvert() {
    }

    public static XxxYyyVo toVo(XxxYyy xxx) {
        XxxYyyVo vo = new XxxYyyVo();
        vo.setId(xxx.getId());
        vo.setName(xxx.getName());
        vo.setStatus(xxx.getStatus());
        vo.setCreateBy(xxx.getCreateBy());
        vo.setCreateTime(xxx.getCreateTime());
        return vo;
    }
}
```

## 步骤 5：Service（service/XxxYyyManageService.java）

```java
package com.cloudai.<service>.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.<service>.convert.XxxYyyConvert;
import com.cloudai.<service>.dto.XxxSaveRequest;
import com.cloudai.<service>.entity.XxxYyy;
import com.cloudai.<service>.mapper.XxxYyyMapper;
import com.cloudai.<service>.vo.XxxYyyVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class XxxYyyManageService {

    /** 错误码按服务分段接续分配：1xxx 通用 / 2xxx 认证 / 3xxx system（现用至 3007，新增从 3008 起）/ 4xxx bpmn */
    private static final int ERR_XXX_NOT_FOUND = 3008;
    private static final int ERR_XXX_DUP = 3009;

    private final XxxYyyMapper xxxMapper;

    /** 只读不加事务注解 */
    public PageResult<XxxYyyVo> pageList(PageQuery query) {
        IPage<XxxYyy> page = xxxMapper.pageList(new Page<>(query.getPageNum(), query.getPageSize()));
        List<XxxYyyVo> rows = page.getRecords().stream().map(XxxYyyConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    public XxxYyyVo findById(Long id) {
        return XxxYyyConvert.toVo(requireXxx(id));
    }

    /** 单表单语句不加事务：MySQL 语句级原子已保证，唯一性并发由 uk + DuplicateKeyException 兜底 */
    public Long save(XxxSaveRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException("名称不能为空");
        }
        Long exists = xxxMapper.countByName(req.getName(), null);
        if (exists > 0) {
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + req.getName());
        }
        XxxYyy xxx = new XxxYyy();
        xxx.setName(req.getName());
        xxx.setStatus(req.getStatus() == null ? XxxYyy.StatusEnum.NORMAL.getCode() : req.getStatus());
        auditCreate(xxx);
        try {
            xxxMapper.save(xxx);
        } catch (DuplicateKeyException e) {
            // 逻辑删除墓碑仍占用唯一键，countByName 查重看不到——捕获兜底转业务码
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + req.getName());
        }
        return xxx.getId();
    }

    /** 单语句不加事务，理由同 save */
    public void update(Long id, XxxSaveRequest req) {
        requireXxx(id);
        Long exists = xxxMapper.countByName(req.getName(), id);
        if (exists > 0) {
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + req.getName());
        }
        XxxYyy xxx = new XxxYyy();
        xxx.setId(id);
        xxx.setName(req.getName());
        xxx.setStatus(req.getStatus());
        xxx.setUpdateBy(SecurityUtils.currentAccount());
        xxx.setUpdateTime(LocalDateTime.now());
        try {
            xxxMapper.update(xxx);
        } catch (DuplicateKeyException e) {
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + req.getName());
        }
    }

    /** 单语句不加事务；本方法若加关联物理清理（下行注释示例）则变为多写，必须 @Transactional */
    public void delete(Long id) {
        requireXxx(id);
        xxxMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        // 有关联关系表时在此物理清理（参考 SysUserManageService.delete → userRoleMapper.deleteByUserId，带 @Transactional）
    }

    // 事务口径：多写语句方法必须 @Transactional(rollbackFor = Exception.class)——部分成功会留脏状态
    // （范本：SysUserManageService.delete 墓碑+关系物理清理 / assignRoles 删旧绑定+批插）；
    // 单表单语句一律不加：语句级原子 + uk 兜底已足够，check-then-act 竞态加事务也防不住（快照读不锁行）

    /** 手写 SQL 无自动填充：审计四值显式构造，插入时 update 值 = create 值 */
    private void auditCreate(XxxYyy xxx) {
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        xxx.setCreateBy(operator);
        xxx.setCreateTime(now);
        xxx.setUpdateBy(operator);
        xxx.setUpdateTime(now);
    }

    private XxxYyy requireXxx(Long id) {
        XxxYyy xxx = xxxMapper.findById(id);
        if (xxx == null) {
            throw new BusinessException(ERR_XXX_NOT_FOUND, "记录不存在");
        }
        return xxx;
    }
}
```

规则：业务校验前置 + 唯一性查重 + DuplicateKey 兜底（**catch 内必须先 `log.error` 记根因再转业务异常**）；**事务只挂多写语句方法**（`@Transactional(rollbackFor = Exception.class)`，范本 `SysUserManageService.delete/assignRoles`；单表单语句不加——语句级原子 + uk 兜底已足够）；方法 ≤50 行目标 / 100 硬上限，入参 >3 封装对象。

## 步骤 6：Controller（controller/XxxYyyController.java）

```java
package com.cloudai.<service>.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.<service>.dto.XxxSaveRequest;
import com.cloudai.<service>.service.XxxYyyManageService;
import com.cloudai.<service>.vo.XxxYyyVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/xxx")
@RequiredArgsConstructor
public class XxxYyyController {

    private final XxxYyyManageService manageService;

    /** 分页查询（VO 出参，不含敏感字段） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('<svc>:xxx:list')")
    public R<PageResult<XxxYyyVo>> page(PageQuery query) {
        PageResult<XxxYyyVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 查询详情（VO 出参） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('<svc>:xxx:list')")
    public R<XxxYyyVo> detail(@PathVariable("id") Long id) {
        XxxYyyVo xxx = manageService.findById(id);
        return R.ok(xxx);
    }

    /** 新增（返回新记录 ID） */
    @PostMapping
    @PreAuthorize("hasAuthority('<svc>:xxx:add')")
    public R<Long> add(@RequestBody XxxSaveRequest req) {
        Long id = manageService.save(req);
        return R.ok(id);
    }

    /** 修改基本信息 */
    @PutMapping
    @PreAuthorize("hasAuthority('<svc>:xxx:edit')")
    public R<Void> edit(@RequestBody XxxSaveRequest req) {
        manageService.update(req.getId(), req);
        return R.ok();
    }

    /** 删除（逻辑删除，关联关系物理清理） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('<svc>:xxx:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }
}
```

说明：两行式只约束"有返回值"的端点（service 结果先落局部变量再 `R.ok(x)`）；无数据端点保持 `service 调用; return R.ok();` 两行；**每个方法必须有 javadoc**；`@PathVariable("id")` 显式命名；领域动作端点用独立 DTO（重置密码/分配角色参考 `SysUserController.resetPassword/assignRoles`）；服务间内部接口放 `controller/feign/` 子包，路径 `/inner/xxx/**`，**首个 /inner 端点必须与网关屏蔽规则同任务落地**。

## 步骤 7：服务间 Feign 客户端（cloud-<svc>-api 模块）

提供 /inner 端点的服务建 api 模块（依赖仅 core-starter，可加 jakarta.validation-api；禁业务/mybatis/web 依赖），结构 `com.cloudai.<svc>.api`：`client/` 接口、`fallback/` 降级工厂、`domain/` 契约模型（类名与 API 契约术语一致）。

**模板 A client**：`@FeignClient(name = "cloud-<svc>", contextId = "<消费语义>Client", path = "/inner/xxx", fallbackFactory = XxxClientFallbackFactory.class)`；方法 `@PostMapping/@GetMapping` + `@PathVariable("x")` 显式命名；返回 `R<契约模型>`。

**模板 B fallbackFactory**：`implements FallbackFactory<XxxClient>`，`create(Throwable cause)` 返回匿名实现——每方法 `log.error("xx服务降级（方法）: 键={}", key, cause)` 后 `R.fail(降级码, "cloud-<svc> 服务不可用")`；降级码按消费方既有终态定制（等价迁移口径，中性码演进记移交）。

**模板 C domain**：契约模型 Serializable + serialVersionUID + 校验注解（@NotBlank/@Size），DB 实体禁直出（投影子集）。

**模板 D 自动装配**：`@AutoConfiguration` 类 @Bean 注册 fallbackFactory + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 一行——api 包不在消费方扫描范围，必须自动装配（引 jar 即生效）。

**消费方接入三件套**：① pom 引 api jar + `spring-cloud-starter-circuitbreaker-resilience4j`（BOM 免版本号）；② `@EnableFeignClients(clients = {XxxClient.class})` 显式列表；③ application.yml：`spring.cloud.openfeign.circuitbreaker.enabled: true` + `client.config.default.connect-timeout: 1000` / `read-timeout: 5000` + `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter: true`。

**陷阱两条**：TimeLimiter 默认 1s 会截短 read 超时（必处置，见上）；开关关闭时 fallback 静默不生效——调用方 `catch (FeignException)` 二层兜底必须保留，降级 R 走既有 `code != SUCCESS` 分支转译域码。

**特例记档**：translate-remote-starter 程序式 client（FeignClientBuilder，common 包不可扫描 + 缓存层降级自洽）不在本模型内。

## 步骤 8：RocketMQ 事务消息与消费（cloud-common-rocketmq-starter）

**何时用**：跨服务写路径有分布式一致性缺口（本地写 + 远端写不能同事务）——同步语义保持（接口签名/返回零变化），远端动作事务消息化 + 结果事件回写收敛（范本：请假发起 → TX_APPROVAL_CREATE → bpmn 建单 → CREATE_RESULT 事件回填 approvalId）。

**生产端三件套**：

1. 生产方法**编排化（去 @Transactional——事务边界归 starter listener）**：校验前置（fail-fast 防白跑 broker）→ 组装实体（id `IdUtil.getSnowflakeNextId()` **预生成**——半消息体先于落库序列化需知 businessKey；审计四值显式）→ `txMessageSender.sendTransactional(topic, tag, keys, payload, channel, bizArg)`。
   - keys=`{businessType}:{businessKey}`；bizArg=预组装实体 **JVM 内透传**（不经 broker 序列化）；`TxMessageSendException`=半消息失败**本地零写**，catch 转「消息服务不可用」业务码（可重试）。
2. `TxLocalExecutor<P>` 实现（@Component，范本 `LeaveCreateTxExecutor`）：`channel()` 通道名 / `payloadType()` / `executeInTx(payload, ctx)`——完整业务写 + **必调 `ctx.setBusinessRef(type, key)`**（mq_tx_log 审计列来源，缺失整体回滚）；实现内**禁 @Transactional**（双事务边界撕裂——COMMIT 决策返回前本地事务必须已提交）。
3. 结果事件通知（反向回写）：普通消息 `syncSend(TOPIC + ":" + tag)`，KEYS=`{businessType}:{businessKey}:{eventType}`；**事件消息模型归提供方 api 模块 `mq/` 子包**（与 Feign 契约同位，范本 `ApprovalEventMessage`/`ApprovalMqTopics`）；事件发送失败语义区分：结果事件失败→重抛重试（确定性重放一致），通知事件失败→log.error+ACK（纠偏兜底）。

**消费端幂等——双层二选一（守护测试检查，二者必有其一）**：

- **L1 通用去重表**（回写/通知类，无业务 uk）：继承 `DedupRocketMQListener`，实现 `dedupGroup()`（与 consumerGroup 同名）+`doConsume(msg)`——基类先插 mq_consume_dedup→DuplicateKey=已消费 ACK 跳过→doConsume 异常**删行放行重试**（失败不删行=后续重投被吞=消息丢失）。
- **L2 业务 uk 幂等**（建单类，业务表有 uk_business）：不继承基类，类上标注 `@UkIdempotentListener("uk 说明")` 豁免 + catch `DuplicateKeyException` 幂等吸收（log+ACK，重投至多建一单）。
- 回写 UPDATE 用**条件写**幂等三防：`WHERE approval_id IS NULL` / `WHERE status=0`（重投/乱序/双写同值无害，未命中=空更新 ACK 记档）——**审批状态回写已升级投影模式（见下方「审批状态投影接入」），业务表条件 UPDATE 模板自 2026-10-09 投影轮起退役**；非审批域的普通事件回写仍用本口径。

**消费失败三分类**：

| 分类 | 处置 |
|---|---|
| 幂等吸收（DuplicateKey） | log + ACK（至多一次生效） |
| 确定性业务失败白名单（码固定可枚举，如审批人无效/已终态） | 转结果事件（FAILED）通知上游收敛 + ACK |
| 未知/系统异常 | 抛出 → 重试 `maxReconsumeTimes=3` → %DLQ% 死信人工（dashboard 巡检，禁止无限重试） |

**审批状态投影接入（2026-10-09 投影轮起新默认，范本 sys_leave）**——业务服务接入审批流**不再自建事件消费者**，四件套：

1. **业务表零状态列**：不建 status/approval_id 快照列（存量表出增量 ALTER DROP COLUMN——删列不可逆记档，状态可从平台反查重建）；终态判定用常量类（`constant/LeaveStatus` 先例：String 域 + `isTerminal`，禁魔法值）。
2. **DDL 复制 approval_projection**（框架表落业务侧库：`uk_business(business_type,business_key)` 一索三用 + `idx_approval_status`，行永不删无 deleted 列——从基线 SQL 或增量脚本 `2026-10-09-approval-projection.sql` 复制）。
3. **一行配置启用**（application.yml，仓库化非密钥）：`cloud.bpmn.projection.enabled: true` + `cloud.bpmn.projection.consumer-group: g_<svc>_approval_event`（**组名必须全新拟定或沿用既有**——改组名=broker offset 重置重放历史事件）。引 cloud-bpmn-api jar 即得唯一事件监听对象（三分支 upsert 投影表）+ 定时对账（默认 60s，Feign 失败本轮放弃不抛），业务零代码。
4. **读路径 mapper JOIN 派生**（XML 手写，业务对投影表只读——守护规则：业务 SQL 禁写投影表/业务模块禁自建 APPROVAL_EVENT_NOTIFY 消费者）：

```xml
<sql id="listColumns">
    l.id, l.title, <!-- ...业务列... -->, l.create_time,
    p.approval_id,
    CAST(CASE WHEN p.create_result = 2 THEN 4 ELSE IFNULL(p.approval_status, 0) END AS CHAR) AS status
</sql>
<!-- LEFT JOIN 投影：无行（存量/事件未达）→ status=0、approval_id NULL；CAST(l.id AS CHAR) 在驱动侧，
     p.business_key 裸列走 uk_business 探针（EXPLAIN 抽查）；CAST CHAR 规避 TINYINT→String 映射 -->
<select id="pageList" resultType="com.cloudai.<service>.vo.XxxYyyVo">
    SELECT <include refid="listColumns"/>
      FROM xxx_yyy l
      LEFT JOIN approval_projection p
        ON p.business_type = #{businessType} AND p.business_key = CAST(l.id AS CHAR)
     WHERE l.apply_user = #{applyUser} AND l.deleted = 0
     ORDER BY l.id DESC
</select>
```

   派生列无实体承载——mapper 返回 VO（resultMap 直出，先例 selectPermsByAccount），convert 层本链路无实体可转；撤销等同步动作成功后调 `ApprovalProjectionReconciler.reconcileByBusiness(type, keys)` 即时对账（best-effort 不抛，事件兜底收敛）。

**配置与陷阱**：`rocketmq.name-server`/`rocketmq.producer.group` 走 Nacos 不进仓库（未配 name-server 时 RocketMQTemplate 不装配，服务可起）；消费组由 `@RocketMQMessageListener(consumerGroup=...)` 自持（投影监听经 `cloud.bpmn.projection.consumer-group` 占位符注入）；**消费无用户上下文**——审计 operator 用系统操作者记档（如 "bpmn-event"）；消息体字段一律 String（Long→String 全局 Jackson 口径，防精度/类型漂移）。

## 步骤 9：xxl-job 分布式调度（2026-10-10 起 @Scheduled 已全量清零，定时任务唯一形态）

**调度纪律**：**新定时任务必须 xxl-job，禁新增 `@Scheduled`**（调度节奏治理权归 admin 控制台——改节奏/暂停/手动补跑不动代码不发版；`@EnableScheduling` 已随清零自各 starter 摘除，宿主自建 `@Scheduled` 不会跑）。守护两规则强制：服务模块禁 `new XxlJobSpringExecutor`（executor 装配归 cloud-common-xxljob-starter）、`@XxlJob` 类必须落 `job/` 包或 api 框架包。存量任务全景（种子 id 锚定）：id 5/6 demo hello world（手动，模板范本）/ id 7 审批投影对账（每分钟，cloud-bpmn-api 薄壳形态）/ id 8/9 MQ 两表清理（每日 03:00，rocketmq-starter，两组各一）。

### 9.1 服务接入（新服务三步）

1. pom 引 `cloud-common-xxljob-starter`；
2. Nacos per-service 追加（共享 `cloud-common.yaml` 已有全局三键 `xxl.job.admin.addresses/executor.logpath/executor.logretentiondays`，勿重复配）：

```yaml
cloud:
  common:
    xxljob:
      enabled: true        # 默认关——未配零装配
xxl:
  job:
    executor:
      appname: <spring.application.name>   # 与执行器组 app_name 一致
      port: 1920x                          # system=19202 / bpmn=19203，新服务顺延
      ip: <物理网卡 IP>                     # 多网卡必钉（见下）；accessToken=default_token
```

3. `spring.config.import` 确认含 `optional:nacos:cloud-common.yaml`（optional 容缺）。

**门控语义**：`enabled` 默认关——admin 容器未起不阻断服务启动（CI/本地无容器安全）；显式开启后 executor 绑定端口，**绑定失败会阻断服务启动**（运维注意）。**注册地址口径**：多网卡机器自动探测不可用（虚拟网卡注册致 admin 容器回调不可达），钉 `ip=物理网卡`（本机 192.168.10.22 先例；127.0.0.1 是容器自身回环不可达）；宿主网卡/IP 变更需同步 Nacos。

### 9.2 任务开发（两种落位形态）

1. **服务本地任务**（默认形态）：`<svc>/src/main/java/com/cloudai/<svc>/job/XxxJobHandler.java`，`@Component` 交容器扫描，方法标 `@XxlJob("xxxJobHandler")`（范本 `CloudDemoJobHandler`）：

```java
@Slf4j
@Component
public class XxxJobHandler {

    /** 任务方法：不抛异常即成功（xxl 缺省成功口径）；参数经 XxlJobHelper.getJobParam() 获取 */
    @XxlJob("xxxJobHandler")
    public void xxxJobHandler() {
        String param = XxlJobHelper.getJobParam();
        XxlJobHelper.log("xxx job start, param=" + param);   // 留 admin 执行日志痕
        // ...业务（吞异常口径按需设计：恒成功观察走服务日志 / 异常上抛=admin 红记录，见 9.5）
        XxlJobHelper.log("xxx job end");
    }
}
```

2. **api jar / starter 框架组件任务**（特例；先例 cloud-bpmn-api projection 包 `ApprovalProjectionReconcileJobHandler` 薄壳、rocketmq-starter `MqTableCleanJob` 直接注解）：框架组件任务落框架模块自身包（薄壳类或既有任务类直接标 `@XxlJob`），bean 经 AutoConfiguration `@Bean` 注册（同门控）——`XxlJobSpringExecutor` 遍历容器全部 bean 定义收集方法级 `@XxlJob`（与注册方式无关，2026-10-10 联调实证）；框架模块 pom 加 xxl-job-core（版本走根 dependencyManagement）；仅注解引用（无 XxlJobHelper 调用）时未接 xxl 的宿主零运行时副作用；薄壳零业务逻辑，编排与异常语义全在被调组件公有方法。**多服务同库语义**（每实例清自己库/处理自己分片）：两组各一任务、handler 名同名（作用域=执行器组，合法）——分片广播仅用于真需数据集分片的场景。

### 9.3 admin 任务种子（SQL 规范）

- 双落位：`scripts/sql/<日期>-<域>.sql` 增量 + `2026-10-09-xxl-job-init.sql` 基线种子段**同步追加**（环境重建完整性）；固定主键 id 顺延，头注标不可重放（重放先 DELETE 对应 id）
- 三要素：执行器组=宿主 appname；`executor_handler` 与 `@XxlJob` 值**逐字一致**；调度 NONE（纯手工）/ CRON（定时）
- 陷阱集（3.5.0 实证，全部踩过）：CRON **秒域步进须 <60**（`0/60` 非法，admin 报 `Increment >= 60` 并自动停任务）；`glue_updatetime` 必须 now()（JobTrigger 解引用无空防护，NULL 即 NPE 卡 pending）；`trigger_status=1` **显式列**（表默认 0=停止）；手工 INSERT `trigger_next_time` 默认 0 落在过去——admin 首扫按 misfire DO_NOTHING 自愈刷新到下一 CRON 点，不双跑

### 9.4 admin 控制台与 REST（3.5.0 口径）

- UI：http://127.0.0.1:18081（**无 /xxl-job-admin context 前缀**），admin/123456；admin 容器常驻不回收；**镜像 tag ≡ xxl-job-core 版本**，升级必须同批
- REST（登录 POST `/auth/doLogin`）：任务列表 `jobinfo/pageList` 参数 `jobGroup/triggerStatus/name/executorHandler/author/offset/pagesize`（非 2.x 的 jobDesc/start/length，旧组合 400）；手动触发 POST `/jobinfo/trigger`（id/executorParam/**addressList= 空串必带**）；执行日志 `joblog/pageList`（**filterTime= 空串必带**——缺这两个空串参数均 400 System Error）
- DB：独立 `xxl_job` 库（官方 8 表逐字 + 本项目种子段）；基线脚本不可重放（重放先 DROP DATABASE——开发库可整库重建）；token 现为公开默认值 `default_token`，生产化换强 token 需 DB 组行与 Nacos 双处同步（移交备忘）

### 9.5 失败语义与告警现状

任务**不抛异常即成功**（xxl 缺省口径）：吞异常=恒成功、失败观察走服务日志 log.error；异常上抛=admin 红记录可见（较 @Scheduled 时代吞异常是**可见性增强**，无自动重试除非配 executor_fail_retry_count）。告警通道未接（alarm_email 空，移交备忘）——生产化前失败仅 admin 页面可见，联调验收时手动触发 + 双侧日志（admin 执行日志 + 服务日志）取证。

## 通用约束（全后端强制；机械项由守护测试保证）

- 同前缀多值配置用 `@ConfigurationProperties` 对象（参考 security-starter 的 JwtProperties），同文件 ≥2 个 @Value 违规
- Java 侧禁魔法数：状态引用内嵌枚举常量（`XxxYyy.StatusEnum.NORMAL.getCode()`），SQL 字面量除外；**内嵌枚举一律 Enum 后缀**（StatusEnum/DeletedEnum，守护测试检查）
- 分层依赖 Controller → Service → Mapper：Controller 禁 import mapper；Feign 内部接口在 `controller/feign/`

## 完成后检查清单

- [ ] 主表每条 SQL 都有 `deleted`（查询=0 / 删除置 1 带 update 审计）？纯关系表物理 DELETE？
- [ ] DDL 每列有 COMMENT（审计列=创建人/创建时间/更新人/更新时间）？
- [ ] **索引**：新表 DDL 已按查询清单设计（uk 兼查重、关联列/高频列覆盖、取舍写明）？迭代新增查询路径已 EXPLAIN 复审既有索引、缺失出增量 ALTER？
- [ ] **事务**：只挂多写语句方法（单表单语句不加，见步骤 5 事务口径）？
- [ ] Mapper/Service 方法名在动词集白名单（find/save/update/pageList/list/delete/count + 领域动作 reset/assign 等）且不带实体名前缀？
- [ ] INSERT 显式审计四值 / UPDATE 两值（Service 构造）；update 单参（审计随实体）、deleteById 三参？
- [ ] 全部 `#{}` 无 `${}`？分页无 LIMIT？`<if>` 标签体全部换行？
- [ ] Controller：两行式 + `@PathVariable` 显式命名 + 每方法 javadoc + 全管理端点 @PreAuthorize + 出参 VO（无 `R<实体>`）+ 入参 DTO（禁 Map）？
- [ ] convert 包 final 类 + 私有构造 + 原生 setter，无三方拷贝工具 import？
- [ ] 权限标识已入 sys_menu 种子并映射 admin 角色？
- [ ] 唯一键查重 + DuplicateKey 兜底（catch 内先 log.error）？错误码按服务分段接续分配？
- [ ] `mvn -f cloud-base/pom.xml clean install -pl cloud-<service> -am` 全绿（含 ArchitectureGuardTest/MapperXmlBindingTest；新 DB 服务复制这两个守护测试）？
- [ ] 起服务按契约 curl 新端点（经网关带 admin token）+ DB 抽查审计字段？
- [ ] 服务模块 `@FeignClient` 零命中（守护测试）？
- [ ] api 模块 `@FeignClient` 均带 `fallbackFactory`（守护测试）？
- [ ] **MQ（涉事务消息/消费时）**：生产 executor 形态（编排化+executeInTx 内 setBusinessRef，实现内无 @Transactional）/ 消费 L1L2 二选一（DedupRocketMQListener 或 @UkIdempotentListener，守护测试检查）/ 失败三分类归位 / topic·group·KEYS·消息体与消息契约逐字一致？
- [ ] **审批流接入（投影轮起新默认）**：业务表零状态列 + approval_projection DDL 落库 + `cloud.bpmn.projection.enabled/consumer-group` 两行配置 + mapper LEFT JOIN 派生读（CAST CHAR）？未自建 APPROVAL_EVENT_NOTIFY 消费者、业务 SQL 未写投影表（守护两规则）？
- [ ] **定时任务（2026-10-10 起）**：新定时任务用 xxl-job @XxlJob（全仓 @Scheduled 已清零，禁新增）？handler 类落 `job/` 包（服务本地）或框架模块包（api/starter 组件特例）？未 new XxlJobSpringExecutor（executor 装配归 starter，守护两规则）？admin 任务种子双落位（增量 + 基线 init 同步，id 顺延）：handler 名与 `@XxlJob` 逐字一致、CRON 秒域步进 <60、glue_updatetime=now()、trigger_status=1 显式列？新服务接入走了 9.1 三步（starter 依赖 + per-service 两键块 + cloud-common.yaml 接线，ip 钉物理网卡）？联调验收手动触发双 code=200 + admin 执行日志与服务日志双侧取证？
