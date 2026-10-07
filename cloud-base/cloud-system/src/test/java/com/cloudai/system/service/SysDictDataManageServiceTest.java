package com.cloudai.system.service;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.dto.DictDataSaveRequest;
import com.cloudai.system.entity.SysDictData;
import com.cloudai.system.entity.SysDictType;
import com.cloudai.system.mapper.SysDictDataMapper;
import com.cloudai.system.mapper.SysDictTypeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字典项管理单测（契约 2026-10-07-dict-api §3：1002/3008/3010/3012 分支）。
 */
@ExtendWith(MockitoExtension.class)
class SysDictDataManageServiceTest {

    @Mock
    private SysDictDataMapper dictDataMapper;
    @Mock
    private SysDictTypeMapper dictTypeMapper;
    @Mock
    private TranslationCacheService translationCacheService;
    @InjectMocks
    private SysDictDataManageService service;

    private DictDataSaveRequest request(Long typeId, String label, String value) {
        DictDataSaveRequest req = new DictDataSaveRequest();
        req.setTypeId(typeId);
        req.setLabel(label);
        req.setValue(value);
        return req;
    }

    @Test
    void save_nullTypeIdRejected() {
        assertThatThrownBy(() -> service.save(request(null, "启用", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("类型不能为空");
        verify(dictDataMapper, never()).save(any(SysDictData.class));
    }

    @Test
    void save_typeNotFoundRejected() {
        when(dictTypeMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.save(request(9L, "启用", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典类型不存在");
        verify(dictDataMapper, never()).save(any(SysDictData.class));
    }

    @Test
    void save_blankLabelRejected() {
        assertThatThrownBy(() -> service.save(request(1L, " ", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标签不能为空");
        verify(dictDataMapper, never()).save(any(SysDictData.class));
    }

    @Test
    void save_blankValueRejected() {
        assertThatThrownBy(() -> service.save(request(1L, "启用", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典值不能为空");
        verify(dictDataMapper, never()).save(any(SysDictData.class));
    }

    @Test
    void save_duplicateValueRejected() {
        when(dictTypeMapper.findById(1L)).thenReturn(new SysDictType());
        when(dictDataMapper.countByTypeValue(1L, "0", null)).thenReturn(1L);
        assertThatThrownBy(() -> service.save(request(1L, "启用", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典项值已存在");
        verify(dictDataMapper, never()).save(any(SysDictData.class));
    }

    @Test
    void pageList_nullTypeIdRejected() {
        assertThatThrownBy(() -> service.pageList(null, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("类型不能为空");
        verify(dictDataMapper, never()).pageListByTypeId(any(), anyLong());
    }

    @Test
    void pageList_typeNotFoundRejected() {
        when(dictTypeMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.pageList(9L, new PageQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典类型不存在");
        verify(dictDataMapper, never()).pageListByTypeId(any(), anyLong());
    }

    @Test
    void update_dataNotFoundRejected() {
        when(dictDataMapper.findById(9L)).thenReturn(null);
        DictDataSaveRequest req = request(1L, "启用", "0");
        req.setId(9L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典项不存在");
        verify(dictDataMapper, never()).update(any(SysDictData.class));
    }

    @Test
    void update_duplicateValueRejected() {
        // 项现值 (typeId=1, value=v0)；请求只改 value=v1（typeId 未传）：按生效 (1, v1) 查重排除自身
        SysDictData current = new SysDictData();
        current.setId(5L);
        current.setDictTypeId(1L);
        current.setValue("v0");
        when(dictDataMapper.findById(5L)).thenReturn(current);
        when(dictDataMapper.countByTypeValue(1L, "v1", 5L)).thenReturn(1L);
        DictDataSaveRequest req = request(null, "启用", "v1");
        req.setId(5L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典项值已存在");
        verify(dictDataMapper, never()).update(any(SysDictData.class));
    }

    @Test
    void delete_dataNotFoundRejected() {
        when(dictDataMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典项不存在");
        verify(dictDataMapper, never()).deleteById(anyLong(), anyString(), any());
    }

    // ---- 翻译缓存失效挂钩（设计 §4.2）----

    private SysDictType typeWithKey(Long id, String dictKey) {
        SysDictType type = new SysDictType();
        type.setId(id);
        type.setDictKey(dictKey);
        return type;
    }

    @Test
    void save_evictsDictCacheOfOwnerType() {
        when(dictTypeMapper.findById(1L)).thenReturn(typeWithKey(1L, "user_status"));
        when(dictDataMapper.countByTypeValue(1L, "0", null)).thenReturn(0L);
        service.save(request(1L, "正常", "0"));
        verify(translationCacheService).deleteDict("user_status");
    }

    @Test
    void update_noMigration_evictsSingleOwnerKey() {
        SysDictData current = new SysDictData();
        current.setId(5L);
        current.setDictTypeId(1L);
        current.setValue("v0");
        when(dictDataMapper.findById(5L)).thenReturn(current);
        when(dictDataMapper.countByTypeValue(1L, "v0", 5L)).thenReturn(0L);
        when(dictTypeMapper.findById(1L)).thenReturn(typeWithKey(1L, "user_status"));
        DictDataSaveRequest req = request(null, "启用", null);
        req.setId(5L);
        service.update(req);
        verify(translationCacheService, times(1)).deleteDict("user_status");
    }

    @Test
    void update_migrationBetweenTypes_evictsBothTypeKeys() {
        SysDictData current = new SysDictData();
        current.setId(5L);
        current.setDictTypeId(1L);
        current.setValue("v0");
        when(dictDataMapper.findById(5L)).thenReturn(current);
        when(dictDataMapper.countByTypeValue(2L, "v0", 5L)).thenReturn(0L);
        when(dictTypeMapper.findById(2L)).thenReturn(typeWithKey(2L, "order_status"));
        when(dictTypeMapper.findById(1L)).thenReturn(typeWithKey(1L, "user_status"));
        DictDataSaveRequest req = request(2L, "启用", "v0");
        req.setId(5L);
        service.update(req);
        verify(translationCacheService).deleteDict("user_status");
        verify(translationCacheService).deleteDict("order_status");
    }

    @Test
    void delete_evictsDictCacheOfOwnerType() {
        SysDictData current = new SysDictData();
        current.setId(5L);
        current.setDictTypeId(1L);
        when(dictDataMapper.findById(5L)).thenReturn(current);
        when(dictTypeMapper.findById(1L)).thenReturn(typeWithKey(1L, "user_status"));
        service.delete(5L);
        verify(translationCacheService).deleteDict("user_status");
    }

    @Test
    void evictFails_mainFlowNotBroken() {
        when(dictTypeMapper.findById(1L)).thenReturn(typeWithKey(1L, "user_status"));
        when(dictDataMapper.countByTypeValue(1L, "0", null)).thenReturn(0L);
        doThrow(new RuntimeException("redis down")).when(translationCacheService).deleteDict("user_status");
        assertThatCode(() -> service.save(request(1L, "正常", "0")))
                .doesNotThrowAnyException();
        verify(dictDataMapper).save(any(SysDictData.class));
    }

    @Test
    void listByDictKey_delegatesToTransCacheService() {
        when(translationCacheService.listDictItems("user_status")).thenReturn(java.util.List.of());
        service.listByDictKey("user_status");
        verify(translationCacheService).listDictItems("user_status");
    }

    // ---- 内置保护（契约 2026-10-07-builtin-protection-api §2：内置字典项一刀切全禁，3016）----

    private SysDictData builtinData(Long id) {
        SysDictData data = new SysDictData();
        data.setId(id);
        data.setDictTypeId(1L);
        data.setValue("0");
        data.setIsBuiltin(SysDictData.BuiltinEnum.BUILT_IN.getCode());
        return data;
    }

    @Test
    void update_builtinDataRejected() {
        when(dictDataMapper.findById(1L)).thenReturn(builtinData(1L));
        DictDataSaveRequest req = request(null, "改", null);
        req.setId(1L);
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置字典项禁止修改")
                .extracting("code")
                .isEqualTo(3016);
        verify(dictDataMapper, never()).update(any(SysDictData.class));
    }

    @Test
    void delete_builtinDataRejected() {
        when(dictDataMapper.findById(1L)).thenReturn(builtinData(1L));
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置字典项禁止删除")
                .extracting("code")
                .isEqualTo(3016);
        verify(dictDataMapper, never()).deleteById(anyLong(), anyString(), any());
    }
}
