package com.cloudai.system.service;

import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录联动查询：账号 → 用户 + 权限标识集合（经 角色→菜单 聚合）。
 * 原 5 连查（角色/启用角色/角色菜单/菜单）收敛为一条 JOIN 查询；空短路语义保留：
 * 用户不存在返回 null，无角色/无菜单聚合为空权限列表。
 */
@Service
@RequiredArgsConstructor
public class SysUserLinkageService {

    private final SysUserMapper userMapper;

    public LoginUserDTO getLoginUserByAccount(String account) {
        SysUser user = userMapper.selectByAccount(account);
        if (user == null) {
            return null;
        }
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(user.getId());
        dto.setAccount(user.getAccount());
        dto.setNickname(user.getNickname());
        dto.setPassword(user.getPassword());
        dto.setStatus(user.getStatus());

        List<String> perms = userMapper.selectPermsByAccount(account);
        dto.setPermissions(perms == null ? List.of() : perms);
        return dto;
    }
}
