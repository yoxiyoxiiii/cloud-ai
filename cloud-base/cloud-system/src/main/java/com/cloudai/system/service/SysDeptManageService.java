package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.DeptSaveRequest;
import com.cloudai.system.dto.DeptTreeNode;
import com.cloudai.system.entity.SysDept;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.util.DeptTreeBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门管理（契约 2026-10-10-data-permission-api §2）：树 CRUD + 删除前置校验。
 * 事务口径：全单语句不加 @Transactional（语句级原子 + uk 兜底，backend-spec 步骤 5）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDeptManageService {

    /** 错误码分段：3xxx system，数据权限域 3026-3034（契约 §8） */
    private static final int ERR_DEPT_NOT_FOUND = 3027;
    private static final int ERR_DEPT_DUP = 3028;
    private static final int ERR_DEPT_BUILTIN = 3029;
    private static final int ERR_DEPT_OCCUPIED = 3030;

    /** 根级父 id（契约 §1：parent_id=0 为根） */
    private static final long ROOT_PARENT_ID = 0L;

    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;

    /** 部门树（契约 §2.1）：全量未删除含停用，sort/id 升序内存组森林 */
    public List<DeptTreeNode> listTree() {
        return DeptTreeBuilder.build(deptMapper.listAll());
    }

    /** 新增部门（契约 §2.2）：parentId 存在 3027 → name 非空白 1002 → 同层重名 3028 → 审计四值 → DuplicateKey 兜底 */
    public Long save(DeptSaveRequest req) {
        if (req.getParentId() == null) {
            throw new BusinessException("父部门不能为空");
        }
        requireParentExists(req.getParentId());
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException("部门名称不能为空");
        }
        Long exists = deptMapper.countByParentAndName(req.getParentId(), req.getName(), null);
        if (exists > 0) {
            throw new BusinessException(ERR_DEPT_DUP, "同层级下已存在同名部门: " + req.getName());
        }
        SysDept dept = new SysDept();
        dept.setParentId(req.getParentId());
        dept.setName(req.getName());
        dept.setSort(req.getSort() == null ? 0 : req.getSort());
        dept.setStatus(req.getStatus() == null ? SysDept.StatusEnum.NORMAL.getCode() : req.getStatus());
        auditCreate(dept);
        try {
            deptMapper.save(dept);
        } catch (DuplicateKeyException e) {
            // 逻辑删除墓碑仍占用 uk_parent_name，countByParentAndName 查重看不到——捕获兜底转业务码
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(ERR_DEPT_DUP, "同层级下已存在同名部门: " + req.getName());
        }
        return dept.getId();
    }

    /** 修改部门（契约 §2.3）：3027 → parentId 禁改 1002 → 空白名 1002 / 重名 3028 → 动态更新两值 */
    public void update(DeptSaveRequest req) {
        SysDept current = requireDept(req.getId());
        if (req.getParentId() != null && !req.getParentId().equals(current.getParentId())) {
            throw new BusinessException("暂不支持修改上级部门");
        }
        if (req.getName() != null && req.getName().isBlank()) {
            throw new BusinessException("部门名称不能为空");
        }
        if (req.getName() != null) {
            Long exists = deptMapper.countByParentAndName(current.getParentId(), req.getName(), req.getId());
            if (exists > 0) {
                throw new BusinessException(ERR_DEPT_DUP, "同层级下已存在同名部门: " + req.getName());
            }
        }
        SysDept dept = new SysDept();
        dept.setId(req.getId());
        dept.setName(req.getName());
        dept.setSort(req.getSort());
        dept.setStatus(req.getStatus());
        dept.setUpdateBy(SecurityUtils.currentAccount());
        dept.setUpdateTime(LocalDateTime.now());
        try {
            deptMapper.update(dept);
        } catch (DuplicateKeyException e) {
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(ERR_DEPT_DUP, "同层级下已存在同名部门: " + req.getName());
        }
    }

    /** 删除部门（契约 §2.4）：3027 → 内置 3029 → 有子部门/挂载在职用户 3030 → 逻辑删 */
    public void delete(Long id) {
        SysDept dept = requireDept(id);
        if (Integer.valueOf(SysDept.BuiltinEnum.BUILT_IN.getCode()).equals(dept.getIsBuiltin())) {
            throw new BusinessException(ERR_DEPT_BUILTIN, "内置部门禁止删除");
        }
        if (deptMapper.countByParentId(id) > 0) {
            throw new BusinessException(ERR_DEPT_OCCUPIED, "部门下存在子部门或在职用户，禁止删除");
        }
        // 在职用户=未删用户（含停用账号，契约 §2.4 口径）
        if (userMapper.countByDeptId(id) > 0) {
            throw new BusinessException(ERR_DEPT_OCCUPIED, "部门下存在子部门或在职用户，禁止删除");
        }
        deptMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    private void requireParentExists(Long parentId) {
        if (parentId == ROOT_PARENT_ID) {
            return;
        }
        requireDept(parentId);
    }

    private SysDept requireDept(Long id) {
        SysDept dept = deptMapper.findById(id);
        if (dept == null) {
            throw new BusinessException(ERR_DEPT_NOT_FOUND, "部门不存在");
        }
        return dept;
    }

    /** 手写 SQL 无自动填充：审计四值显式构造，插入时 update 值 = create 值 */
    private void auditCreate(SysDept dept) {
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        dept.setCreateBy(operator);
        dept.setCreateTime(now);
        dept.setUpdateBy(operator);
        dept.setUpdateTime(now);
    }
}
