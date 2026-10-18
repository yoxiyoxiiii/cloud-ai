package com.cloudai.system.service;

import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.entity.SysDept;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 登录联动查询：账号 → 用户 + 权限标识集合（经 角色→菜单 聚合）+ 启用角色 id + 部门祖先链。
 * 原 5 连查（角色/启用角色/角色菜单/菜单）收敛为一条 JOIN 查询；空短路语义保留：
 * 用户不存在返回 null，无角色/无菜单聚合为空权限列表，无部门聚合为空链。
 */
@Service
@RequiredArgsConstructor
public class SysUserLinkageService {

    /** 根级父 id（sys_dept.parent_id=0 为根） */
    private static final long ROOT_PARENT_ID = 0L;

    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;

    public LoginUserDTO findByAccount(String account) {
        SysUser user = userMapper.findByAccount(account);
        if (user == null) {
            return null;
        }
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(user.getId());
        dto.setAccount(user.getAccount());
        dto.setNickname(user.getNickname());
        dto.setPassword(user.getPassword());
        dto.setStatus(user.getStatus());

        List<String> perms = userMapper.listPermsByAccount(account);
        dto.setPermissions(perms == null ? List.of() : perms);
        List<Long> roleIds = userMapper.listRoleIdsByAccount(account);
        dto.setRoleIds(roleIds == null ? List.of() : roleIds);
        dto.setDeptIds(resolveDeptChain(user.getDeptId()));
        return dto;
    }

    /**
     * 部门祖先链求值：自身部门沿 parent_id 向上收集至根，输出 root→self 顺序。
     * 未挂部门（dept_id 为 NULL）→ 空链；祖先行被软删（断链）即止防御；
     * 环状脏数据由已访问集防御，不会死循环。
     */
    private List<Long> resolveDeptChain(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        Map<Long, SysDept> byId = deptMapper.listAll().stream()
                .collect(Collectors.toMap(SysDept::getId, Function.identity()));
        List<Long> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long current = deptId;
        while (current != null && current != ROOT_PARENT_ID && byId.containsKey(current) && visited.add(current)) {
            chain.add(current);
            current = byId.get(current).getParentId();
        }
        Collections.reverse(chain);
        return chain;
    }
}
