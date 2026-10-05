---
name: backend-crud
description: 在任意后端服务（cloud-system/cloud-bpmn/新增 DB 服务）新增实体/管理端点/CRUD 五件套时使用——建表、实体、Mapper 接口、mapper XML、Service、Controller 模板与规范检查清单。凡涉及"新增表/新增接口/新增 CRUD/新增 /inner 端点"均应触发本技能。
---

# 后端 CRUD 五件套模板（适用于所有 DB 服务）

按此技能创建的代码自动满足 CLAUDE.md"编码规范"与 ArchitectureGuardTest。以新增实体 `XxxYyy`（表 `xxx_yyy`）为例，逐层给出模板。

## 步骤 0：建表 SQL（scripts/sql/ 追加）

```sql
CREATE TABLE xxx_yyy (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(30) NOT NULL COMMENT '名称',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL,
    create_time DATETIME     DEFAULT NULL,
    update_by   VARCHAR(30)  DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = 'Xxx说明';
```

要点：审计四列 + `deleted TINYINT NOT NULL DEFAULT 0`；唯一键 `uk_xxx`；**每列必须有 COMMENT（含关联表与审计列）**；纯关系表（无业务生命周期的关联）**不要** deleted/审计列，但列同样要 COMMENT。

权限种子（管理端点需要）：权限体系集中在 cloud-system 的 sys_menu——追加 INSERT（perms 形如 `<svc>:xxx:list/add/edit/remove`，`<svc>` 为本服务短名如 system/bpmn）+ `INSERT INTO sys_role_menu ... SELECT 1, id FROM sys_menu` 增量。

## 步骤 1：实体（entity/XxxYyy.java）

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
}
```

注解仅为元数据（手写 SQL 不依赖 @TableLogic 自动语义）；敏感字段（如密码）加 `@ToString.Exclude` + `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`。

## 步骤 2：Mapper 接口（mapper/XxxYyyMapper.java）

```java
package com.cloudai.<service>.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.<service>.entity.XxxYyy;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface XxxYyyMapper {

    XxxYyy selectXxxById(@Param("id") Long id);

    IPage<XxxYyy> selectXxxPage(Page<XxxYyy> page);

    Long countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertXxx(XxxYyy xxx);

    int updateXxx(@Param("xxx") XxxYyy xxx,
                  @Param("updateBy") String updateBy,
                  @Param("updateTime") LocalDateTime updateTime);

    int deleteXxxById(@Param("id") Long id,
                      @Param("updateBy") String updateBy,
                      @Param("updateTime") LocalDateTime updateTime);
}
```

禁止 `extends BaseMapper`；聚合查询（跨表取权限等）放相关主表 Mapper（参考 SysUserMapper.selectPermsByAccount 的 JOIN）。

## 步骤 3：mapper XML（resources/mapper/XxxYyyMapper.xml）

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.cloudai.<service>.mapper.XxxYyyMapper">

    <sql id="allColumns">
        id, name, status, create_by, create_time, update_by, update_time, deleted
    </sql>

    <select id="selectXxxById" resultType="com.cloudai.<service>.entity.XxxYyy">
        SELECT <include refid="allColumns"/> FROM xxx_yyy
        WHERE id = #{id} AND deleted = 0
    </select>

    <!-- 分页：不写 LIMIT，PaginationInnerInterceptor 接管（maxLimit 200） -->
    <select id="selectXxxPage" resultType="com.cloudai.<service>.entity.XxxYyy">
        SELECT <include refid="allColumns"/> FROM xxx_yyy
        WHERE deleted = 0
        ORDER BY id DESC
    </select>

    <select id="countByName" resultType="long">
        SELECT COUNT(*) FROM xxx_yyy
        WHERE name = #{name} AND deleted = 0
        <if test="excludeId != null">AND id != #{excludeId}</if>
    </select>

    <insert id="insertXxx">
        INSERT INTO xxx_yyy (name, status, create_by, create_time, update_by, update_time)
        VALUES (#{name}, #{status}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime})
    </insert>

    <!-- 动态列 + <set> 剥尾逗号；update_by 匿名保留原值（<if>） -->
    <update id="updateXxx">
        UPDATE xxx_yyy
        <set>
            <if test="xxx.name != null">name = #{xxx.name},</if>
            <if test="xxx.status != null">status = #{xxx.status},</if>
            <if test="updateBy != null">update_by = #{updateBy},</if>
            update_time = #{updateTime},
        </set>
        WHERE id = #{xxx.id} AND deleted = 0
    </update>

    <!-- 逻辑删除 = 墓碑 UPDATE，带审计两值 -->
    <update id="deleteXxxById">
        UPDATE xxx_yyy SET deleted = 1
        <if test="updateBy != null">, update_by = #{updateBy}</if>
        , update_time = #{updateTime}
        WHERE id = #{id} AND deleted = 0
    </update>
</mapper>
```

规则：主表 SQL 必带 `deleted`（查询 0 / 删除置 1）；只用 `#{}`；纯关系表物理 DELETE + `<foreach>` 批量插入（service 层空列表跳过）。

## 步骤 4：Service（service/XxxYyyManageService.java）

```java
package com.cloudai.<service>.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.<service>.entity.XxxYyy;
import com.cloudai.<service>.mapper.XxxYyyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class XxxYyyManageService {

    /** 错误码按服务分段接续分配：1xxx 通用 / 2xxx 认证 / 3xxx system（现用至 3007）/ 4xxx bpmn */
    private static final int ERR_XXX_NOT_FOUND = 3008;
    private static final int ERR_XXX_DUP = 3009;

    private final XxxYyyMapper xxxMapper;

    public PageResult<XxxYyy> page(PageQuery query) {
        IPage<XxxYyy> page = xxxMapper.selectXxxPage(new Page<>(query.getPageNum(), query.getPageSize()));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public XxxYyy detail(Long id) {
        XxxYyy xxx = requireXxx(id);
        return xxx;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(XxxYyy xxx) {
        if (xxx.getName() == null || xxx.getName().isBlank()) {
            throw new BusinessException("名称不能为空");
        }
        Long exists = xxxMapper.countByName(xxx.getName(), null);
        if (exists > 0) {
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + xxx.getName());
        }
        auditCreate(xxx);
        try {
            xxxMapper.insertXxx(xxx);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 查重看不到并发插入与墓碑占键，兜底转业务码
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + xxx.getName());
        }
        return xxx.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void edit(XxxYyy xxx) {
        requireXxx(xxx.getId());
        if (xxx.getName() != null) {
            Long exists = xxxMapper.countByName(xxx.getName(), xxx.getId());
            if (exists > 0) {
                throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + xxx.getName());
            }
        }
        try {
            xxxMapper.updateXxx(xxx, SecurityUtils.currentAccount(), LocalDateTime.now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ERR_XXX_DUP, "名称已存在: " + xxx.getName());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireXxx(id);
        xxxMapper.deleteXxxById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        // 有关联关系表时在此物理清理
    }

    private void auditCreate(XxxYyy xxx) {
        LocalDateTime now = LocalDateTime.now();
        String operator = SecurityUtils.currentAccount();
        xxx.setCreateBy(operator);
        xxx.setCreateTime(now);
        xxx.setUpdateBy(operator);
        xxx.setUpdateTime(now);
    }

    private XxxYyy requireXxx(Long id) {
        XxxYyy xxx = xxxMapper.selectXxxById(id);
        if (xxx == null) {
            throw new BusinessException(ERR_XXX_NOT_FOUND, "记录不存在");
        }
        return xxx;
    }
}
```

## 步骤 5：Controller（controller/XxxYyyController.java）

```java
package com.cloudai.<service>.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.<service>.entity.XxxYyy;
import com.cloudai.<service>.service.XxxYyyManageService;
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

    /** 分页查询（每个方法必须有 javadoc；入参/返回一律对象，禁止 Map） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('<svc>:xxx:list')")
    public R<PageResult<XxxYyy>> page(PageQuery query) {
        PageResult<XxxYyy> page = manageService.page(query);
        return R.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('<svc>:xxx:list')")
    public R<XxxYyy> detail(@PathVariable("id") Long id) {
        XxxYyy xxx = manageService.detail(id);
        return R.ok(xxx);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('<svc>:xxx:add')")
    public R<Long> add(@RequestBody XxxYyy xxx) {
        Long id = manageService.add(xxx);
        return R.ok(id);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('<svc>:xxx:edit')")
    public R<Void> edit(@RequestBody XxxYyy xxx) {
        manageService.edit(xxx);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('<svc>:xxx:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.remove(id);
        return R.ok();
    }
}
```

说明：两行式只约束"有返回值"的端点（service 结果先落变量再 `R.ok(x)`）；无数据端点（删除/更新）保持 `service 调用; return R.ok();` 两行。服务间内部接口放 `controller/feign/` 子包（参考 InnerUserController），路径 `/inner/xxx/**`。多字段入参（如重置密码）建独立 Request DTO，禁止 Map 接参。

**属性注入**：同前缀多值配置（如 cloud.jwt.*）用 `@ConfigurationProperties` 对象注入（参考 security-starter 的 JwtProperties），不散装 @Value；**Service 层 catch 后必须 `log.error` 记录根因再转业务异常**；方法 ≤50 行（100 硬上限）、入参 >3 封装对象。

## 完成后检查清单

- [ ] 主表每条 SQL 都有 `deleted`（查询=0 / 删除置 1 带 update 两值）？
- [ ] INSERT 显式审计四值 / UPDATE 两值（Service 构造）？
- [ ] 全部 `#{}`，无 `${}`？分页无 LIMIT？
- [ ] Controller 两行式 + `@PathVariable("id")` 显式 + 每个管理端点有 @PreAuthorize？
- [ ] 权限标识已入 sys_menu 种子（cloud-system 库）并映射 admin 角色？
- [ ] 唯一键查重 + DuplicateKey 兜底？错误码按本服务分段接续分配？
- [ ] `mvn -f cloud-base/pom.xml clean install -pl cloud-<service> -am` 全绿？本服务若有 ArchitectureGuardTest/MapperXmlBindingTest 一并通过（cloud-system 已有，新 DB 服务建议复制这两个守护测试）？
- [ ] 起服务 curl 新端点（带 admin X-User-* header 直连）验证 + DB 抽查审计字段？
