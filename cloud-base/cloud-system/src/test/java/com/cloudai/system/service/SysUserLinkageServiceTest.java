package com.cloudai.system.service;

import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.entity.SysDept;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 登录联动聚合单测：roleIds（启用角色 id，启用过滤在 SQL 侧——MapperXmlBindingTest 钉口径）
 * 与 deptIds（祖先链 root→self，断链即止）两段新聚合的空值/顺序/防御分支。
 */
@ExtendWith(MockitoExtension.class)
class SysUserLinkageServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysDeptMapper deptMapper;
    @InjectMocks
    private SysUserLinkageService linkageService;

    private SysUser user(Long deptId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setAccount("e2e_user");
        user.setDeptId(deptId);
        return user;
    }

    private SysDept dept(Long id, Long parentId) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parentId);
        return dept;
    }

    @Test
    void noRolesAggregatesEmptyList() {
        when(userMapper.findByAccount("e2e_user")).thenReturn(user(null));
        when(userMapper.listPermsByAccount("e2e_user")).thenReturn(List.of("ai:kb:list"));
        when(userMapper.listRoleIdsByAccount("e2e_user")).thenReturn(null);

        LoginUserDTO dto = linkageService.findByAccount("e2e_user");

        assertThat(dto.getRoleIds()).isEmpty();
    }

    @Test
    void roleIdsRelayEnabledRoleQueryResult() {
        // mapper 按启用口径（r.status=0 AND r.deleted=0）只返回启用角色 id，service 原样透传
        when(userMapper.findByAccount("e2e_user")).thenReturn(user(null));
        when(userMapper.listPermsByAccount("e2e_user")).thenReturn(List.of());
        when(userMapper.listRoleIdsByAccount("e2e_user")).thenReturn(List.of(2L, 5L));

        LoginUserDTO dto = linkageService.findByAccount("e2e_user");

        assertThat(dto.getRoleIds()).containsExactly(2L, 5L);
    }

    @Test
    void noDeptAggregatesEmptyChain() {
        when(userMapper.findByAccount("e2e_user")).thenReturn(user(null));
        when(userMapper.listPermsByAccount("e2e_user")).thenReturn(List.of());
        when(userMapper.listRoleIdsByAccount("e2e_user")).thenReturn(List.of());

        LoginUserDTO dto = linkageService.findByAccount("e2e_user");

        assertThat(dto.getDeptIds()).isEmpty();
    }

    @Test
    void deepDeptChainOrderedRootToSelf() {
        // 链 1(根) → 3 → 12，用户挂 12：输出 root→self = [1,3,12]
        when(userMapper.findByAccount("e2e_user")).thenReturn(user(12L));
        when(userMapper.listPermsByAccount("e2e_user")).thenReturn(List.of());
        when(userMapper.listRoleIdsByAccount("e2e_user")).thenReturn(List.of());
        when(deptMapper.listAll()).thenReturn(List.of(
                dept(1L, 0L), dept(3L, 1L), dept(12L, 3L), dept(9L, 1L)));

        LoginUserDTO dto = linkageService.findByAccount("e2e_user");

        assertThat(dto.getDeptIds()).containsExactly(1L, 3L, 12L);
    }

    @Test
    void softDeletedAncestorStopsChain() {
        // 祖先 1 已软删（不在 listAll 未删除行中）：链在 3 处断，输出 [3,12]（防御断链即止）
        when(userMapper.findByAccount("e2e_user")).thenReturn(user(12L));
        when(userMapper.listPermsByAccount("e2e_user")).thenReturn(List.of());
        when(userMapper.listRoleIdsByAccount("e2e_user")).thenReturn(List.of());
        when(deptMapper.listAll()).thenReturn(List.of(dept(3L, 1L), dept(12L, 3L)));

        LoginUserDTO dto = linkageService.findByAccount("e2e_user");

        assertThat(dto.getDeptIds()).containsExactly(3L, 12L);
    }

    @Test
    void unknownUserReturnsNull() {
        when(userMapper.findByAccount("ghost")).thenReturn(null);

        assertThat(linkageService.findByAccount("ghost")).isNull();
    }
}
