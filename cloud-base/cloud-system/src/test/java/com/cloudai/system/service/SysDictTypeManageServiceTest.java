package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.dto.DictTypeSaveRequest;
import com.cloudai.system.entity.SysDictType;
import com.cloudai.system.mapper.SysDictDataMapper;
import com.cloudai.system.mapper.SysDictTypeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字典类型管理单测（契约 2026-10-07-dict-api §2：1002/3008/3009/3011 分支）。
 */
@ExtendWith(MockitoExtension.class)
class SysDictTypeManageServiceTest {

    @Mock
    private SysDictTypeMapper dictTypeMapper;
    @Mock
    private SysDictDataMapper dictDataMapper;
    @Mock
    private TranslationCacheService translationCacheService;
    @InjectMocks
    private SysDictTypeManageService service;

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder 是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String account) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private DictTypeSaveRequest request(String dictName, String dictKey) {
        DictTypeSaveRequest req = new DictTypeSaveRequest();
        req.setDictName(dictName);
        req.setDictKey(dictKey);
        return req;
    }

    @Test
    void save_blankNameRejected() {
        assertThatThrownBy(() -> service.save(request(" ", "user_status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典名称不能为空");
        verify(dictTypeMapper, never()).save(any(SysDictType.class));
    }

    @Test
    void save_blankKeyRejected() {
        assertThatThrownBy(() -> service.save(request("用户状态", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典键不能为空");
        verify(dictTypeMapper, never()).save(any(SysDictType.class));
    }

    @Test
    void save_duplicateKeyRejected() {
        when(dictTypeMapper.countByDictKey("user_status", null)).thenReturn(1L);
        assertThatThrownBy(() -> service.save(request("用户状态", "user_status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典键已存在");
        verify(dictTypeMapper, never()).save(any(SysDictType.class));
    }

    @Test
    void save_duplicateKeyFallbackOnUniqueViolation() {
        // 预检通过但 INSERT 撞唯一键（并发 TOCTOU / 墓碑占键）：DuplicateKeyException 兜底转 3009
        when(dictTypeMapper.countByDictKey("user_status", null)).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_dict_key")).when(dictTypeMapper).save(any(SysDictType.class));
        assertThatThrownBy(() -> service.save(request("用户状态", "user_status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典键已存在");
    }

    @Test
    void update_typeNotFoundRejected() {
        when(dictTypeMapper.findById(9L)).thenReturn(null);
        DictTypeSaveRequest req = request("用户状态", "user_status");
        req.setId(9L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典类型不存在");
        verify(dictTypeMapper, never()).update(any(SysDictType.class));
    }

    @Test
    void update_blankNameRejected() {
        when(dictTypeMapper.findById(1L)).thenReturn(new SysDictType());
        DictTypeSaveRequest req = request("", null);
        req.setId(1L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典名称不能为空");
        verify(dictTypeMapper, never()).update(any(SysDictType.class));
    }

    @Test
    void update_duplicateKeyRejected() {
        when(dictTypeMapper.findById(2L)).thenReturn(new SysDictType());
        when(dictTypeMapper.countByDictKey("user_status", 2L)).thenReturn(1L);
        DictTypeSaveRequest req = request("用户状态", "user_status");
        req.setId(2L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典键已存在");
        verify(dictTypeMapper, never()).update(any(SysDictType.class));
    }

    @Test
    void delete_typeNotFoundRejected() {
        when(dictTypeMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典类型不存在");
        verify(dictTypeMapper, never()).deleteById(anyLong(), anyString(), any());
    }

    @Test
    void delete_typeWithItemsRejected() {
        when(dictTypeMapper.findById(1L)).thenReturn(new SysDictType());
        when(dictDataMapper.countByTypeId(1L)).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先删除字典项");
        verify(dictTypeMapper, never()).deleteById(anyLong(), anyString(), any());
    }

    @Test
    void delete_emptyTypeDeleted() {
        loginAs("admin");
        when(dictTypeMapper.findById(1L)).thenReturn(new SysDictType());
        when(dictDataMapper.countByTypeId(1L)).thenReturn(0L);
        service.delete(1L);
        verify(dictTypeMapper).deleteById(eq(1L), eq("admin"), any());
    }

    // ---- 翻译缓存失效挂钩（设计 §4.2）----

    @Test
    void save_evictsDictCacheOfNewKey() {
        loginAs("admin");
        when(dictTypeMapper.countByDictKey("new_status", null)).thenReturn(0L);
        service.save(request("新状态", "new_status"));
        verify(translationCacheService).deleteDict("new_status");
    }

    @Test
    void update_keyChanged_evictsBothOldAndNewKey() {
        loginAs("admin");
        SysDictType current = new SysDictType();
        current.setId(1L);
        current.setDictKey("old_status");
        when(dictTypeMapper.findById(1L)).thenReturn(current);
        when(dictTypeMapper.countByDictKey("new_status", 1L)).thenReturn(0L);
        DictTypeSaveRequest req = request("用户状态", "new_status");
        req.setId(1L);
        service.update(req);
        verify(translationCacheService).deleteDict("old_status");
        verify(translationCacheService).deleteDict("new_status");
    }

    @Test
    void update_keyUnchanged_evictsSingleKey() {
        loginAs("admin");
        SysDictType current = new SysDictType();
        current.setId(1L);
        current.setDictKey("user_status");
        when(dictTypeMapper.findById(1L)).thenReturn(current);
        DictTypeSaveRequest req = request("用户状态v2", null);
        req.setId(1L);
        service.update(req);
        verify(translationCacheService, times(1)).deleteDict("user_status");
    }

    @Test
    void delete_evictsDictCacheOfDeletedKey() {
        loginAs("admin");
        SysDictType type = new SysDictType();
        type.setDictKey("user_status");
        when(dictTypeMapper.findById(1L)).thenReturn(type);
        when(dictDataMapper.countByTypeId(1L)).thenReturn(0L);
        service.delete(1L);
        verify(translationCacheService).deleteDict("user_status");
    }

    @Test
    void evictFails_mainFlowNotBroken() {
        loginAs("admin");
        when(dictTypeMapper.countByDictKey("new_status", null)).thenReturn(0L);
        doThrow(new RuntimeException("redis down")).when(translationCacheService).deleteDict("new_status");
        assertThatCode(() -> service.save(request("新状态", "new_status")))
                .doesNotThrowAnyException();
        verify(dictTypeMapper).save(any(SysDictType.class));
    }
}
