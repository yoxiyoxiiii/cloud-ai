package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.convert.SysRoleConvert;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import com.cloudai.system.vo.SysRoleVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysRoleManageService {

    /** 错误码分段：3xxx system，保护域 3013-3017（契约 2026-10-07-builtin-protection-api §4） */
    private static final int ERR_BUILTIN = 3013;

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PageResult<SysRoleVo> pageList(PageQuery query) {
        IPage<SysRole> page = roleMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()));
        List<SysRoleVo> rows = page.getRecords().stream().map(SysRoleConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    public List<SysRoleVo> listEnabled() {
        return roleMapper.listEnabled().stream().map(SysRoleConvert::toVo).toList();
    }

    public Long save(SysRole role) {
        assertRoleKeyValid(role.getRoleKey());
        assertRoleKeyFree(role.getRoleKey(), null);
        // 手写 SQL 无 MetaObjectHandler 自动填充：审计四值显式传入，插入时 update 值 = create 值
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        role.setCreateBy(operator);
        role.setCreateTime(now);
        role.setUpdateBy(operator);
        role.setUpdateTime(now);
        try {
            roleMapper.save(role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
        return role.getId();
    }

    public void update(SysRole role) {
        SysRole current = requireRole(role.getId());
        // 内置保护（契约 §2 矩阵）：内置角色修改一刀切全禁（null-safe 比对，兼容 is_builtin 未加载的 null）
        if (Integer.valueOf(SysRole.BuiltinEnum.BUILT_IN.getCode()).equals(current.getIsBuiltin())) {
            throw new BusinessException(ERR_BUILTIN, "内置角色禁止修改");
        }
        if (role.getRoleKey() != null) {
            assertRoleKeyValid(role.getRoleKey());
            assertRoleKeyFree(role.getRoleKey(), role.getId());
        }
        role.setUpdateBy(SecurityUtils.currentAccount());
        role.setUpdateTime(LocalDateTime.now());
        try {
            roleMapper.update(role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
    }

    private void assertRoleKeyValid(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            throw new BusinessException("角色标识不能为空");
        }
    }

    /** 预检（编辑时排除自身）；逻辑删除墓碑仍占 uk_role_key，并发/墓碑场景由 DuplicateKey 兜底 */
    private void assertRoleKeyFree(String roleKey, Long excludeId) {
        Long count = roleMapper.countByRoleKey(roleKey, excludeId);
        if (count > 0) {
            throw new BusinessException(3003, "角色标识已存在: " + roleKey);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole current = requireRole(id);
        // 内置保护：admin 角色是权限体系锚点，删除即全员无权限
        if (Integer.valueOf(SysRole.BuiltinEnum.BUILT_IN.getCode()).equals(current.getIsBuiltin())) {
            throw new BusinessException(ERR_BUILTIN, "内置角色禁止删除");
        }
        roleMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        roleMenuMapper.deleteByRoleId(id);
        userRoleMapper.deleteByRoleId(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        SysRole current = requireRole(roleId);
        // 内置保护：绑定操作整体拒绝——清空/摘除绑定等同删除（无权限锁死）；种子绑新菜单走 SQL 惯例
        if (Integer.valueOf(SysRole.BuiltinEnum.BUILT_IN.getCode()).equals(current.getIsBuiltin())) {
            throw new BusinessException(ERR_BUILTIN, "内置角色禁止修改权限");
        }
        roleMenuMapper.deleteByRoleId(roleId);
        if (menuIds != null) {
            List<SysRoleMenu> list = menuIds.stream().distinct()
                    .map(menuId -> {
                        SysRoleMenu rm = new SysRoleMenu();
                        rm.setRoleId(roleId);
                        rm.setMenuId(menuId);
                        rm.setCreateTime(LocalDateTime.now());
                        return rm;
                    }).toList();
            if (!list.isEmpty()) {
                roleMenuMapper.saveBatch(list);
            }
        }
    }

    public List<Long> listMenuIds(Long roleId) {
        return roleMenuMapper.listMenuIdsByRoleId(roleId);
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.findById(id);
        if (role == null) {
            throw new BusinessException(3004, "角色不存在");
        }
        return role;
    }
}
