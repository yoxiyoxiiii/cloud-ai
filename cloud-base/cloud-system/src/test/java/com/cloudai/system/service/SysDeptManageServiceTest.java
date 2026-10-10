package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.DeptSaveRequest;
import com.cloudai.system.entity.SysDept;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 部门管理单测（契约 §2）：save 链（父空/父不存在/名空/同层重名）/ update 禁改上级 /
 * delete 三拦（内置 3029 / 有子部门 3030 / 挂在职用户 3030）——mapper mock，树组装见 DeptTreeBuilderTest。
 */
@ExtendWith(MockitoExtension.class)
class SysDeptManageServiceTest {

    @Mock
    private SysDeptMapper deptMapper;
    @Mock
    private SysUserMapper userMapper;
    @InjectMocks
    private SysDeptManageService service;

    // ---- save 链 4 例 ----

    @Test
    void save_nullParentRejected_1002() {
        DeptSaveRequest req = request(null, "rd");

        BusinessException ex = catchThrowableOfType(() -> service.save(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("父部门不能为空");
        verifyNoInteractions(deptMapper);
    }

    @Test
    void save_parentNotFoundRejected_3027() {
        DeptSaveRequest req = request(99L, "rd");
        when(deptMapper.findById(99L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> service.save(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3027);
    }

    @Test
    void save_blankNameRejected_1002() {
        DeptSaveRequest req = request(0L, " ");
        // parentId=0 根下放行，不触 findById

        BusinessException ex = catchThrowableOfType(() -> service.save(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("部门名称不能为空");
        verifyNoInteractions(deptMapper);
    }

    @Test
    void save_sameLevelDuplicateNameRejected_3028() {
        DeptSaveRequest req = request(1L, "rd");
        when(deptMapper.findById(1L)).thenReturn(dept(1L));
        when(deptMapper.countByParentAndName(1L, "rd", null)).thenReturn(1L);

        BusinessException ex = catchThrowableOfType(() -> service.save(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3028);
        assertThat(ex.getMessage()).contains("rd");
        verify(deptMapper, org.mockito.Mockito.never()).save(any(SysDept.class));
    }

    @Test
    void save_happyPathInsertsWithDefaultsAndAudit() {
        DeptSaveRequest req = request(0L, "rd");
        when(deptMapper.countByParentAndName(0L, "rd", null)).thenReturn(0L);

        service.save(req);

        // sort/status 缺省 0/正常 + 审计四值显式（无登录上下文 operator=null 合法）
        ArgumentCaptor<SysDept> captor = ArgumentCaptor.forClass(SysDept.class);
        verify(deptMapper).save(captor.capture());
        assertThat(captor.getValue().getSort()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(SysDept.StatusEnum.NORMAL.getCode());
        assertThat(captor.getValue().getCreateTime()).isNotNull();
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
    }

    // ---- update 禁改上级 1 例 ----

    @Test
    void update_parentChangeRejected_1002() {
        DeptSaveRequest req = request(2L, "rd");
        req.setId(5L);
        when(deptMapper.findById(5L)).thenReturn(dept(5L));

        BusinessException ex = catchThrowableOfType(() -> service.update(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("暂不支持修改上级部门");
        verify(deptMapper, org.mockito.Mockito.never()).update(any(SysDept.class));
    }

    // ---- delete 三拦 3 例 ----

    @Test
    void delete_builtinRejected_3029() {
        SysDept builtin = dept(1L);
        builtin.setIsBuiltin(1);
        when(deptMapper.findById(1L)).thenReturn(builtin);

        BusinessException ex = catchThrowableOfType(() -> service.delete(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3029);
        verify(deptMapper, org.mockito.Mockito.never()).deleteById(any(), any(), any());
    }

    @Test
    void delete_hasChildrenRejected_3030() {
        when(deptMapper.findById(5L)).thenReturn(dept(5L));
        when(deptMapper.countByParentId(5L)).thenReturn(2L);

        BusinessException ex = catchThrowableOfType(() -> service.delete(5L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3030);
        verifyNoInteractions(userMapper);
    }

    @Test
    void delete_hasUsersRejected_3030() {
        when(deptMapper.findById(5L)).thenReturn(dept(5L));
        when(deptMapper.countByParentId(5L)).thenReturn(0L);
        when(userMapper.countByDeptId(5L)).thenReturn(3L);

        BusinessException ex = catchThrowableOfType(() -> service.delete(5L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3030);
        verify(deptMapper, org.mockito.Mockito.never()).deleteById(any(), any(), any());
    }

    @Test
    void delete_notFoundRejected_3027() {
        when(deptMapper.findById(9L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> service.delete(9L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3027);
    }

    @Test
    void delete_leafWithoutUsers_logicallyDeleted() {
        when(deptMapper.findById(5L)).thenReturn(dept(5L));
        when(deptMapper.countByParentId(5L)).thenReturn(0L);
        when(userMapper.countByDeptId(5L)).thenReturn(0L);

        service.delete(5L);

        verify(deptMapper).deleteById(eq(5L), any(), any());
    }

    // ---- 脚手架 ----

    private DeptSaveRequest request(Long parentId, String name) {
        DeptSaveRequest req = new DeptSaveRequest();
        req.setParentId(parentId);
        req.setName(name);
        return req;
    }

    private SysDept dept(Long id) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(0L);
        dept.setName("d" + id);
        dept.setStatus(0);
        dept.setIsBuiltin(0);
        return dept;
    }
}
