package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录联动查询：账号 → 用户 + 权限标识集合（经 角色→菜单 聚合）
 */
@Service
@RequiredArgsConstructor
public class SysUserLinkageService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    public LoginUserDTO getLoginUserByAccount(String account) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, account)
                .last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(user.getId());
        dto.setAccount(user.getAccount());
        dto.setNickname(user.getNickname());
        dto.setPassword(user.getPassword());
        dto.setStatus(user.getStatus());

        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<Long> enabledRoleIds = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds).eq(SysRole::getStatus, 0))
                .stream().map(SysRole::getId).toList();
        if (enabledRoleIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, enabledRoleIds))
                .stream().map(SysRoleMenu::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<String> perms = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .in(SysMenu::getId, menuIds).eq(SysMenu::getStatus, 0))
                .stream().map(SysMenu::getPerms)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .toList();
        dto.setPermissions(perms);
        return dto;
    }
}
