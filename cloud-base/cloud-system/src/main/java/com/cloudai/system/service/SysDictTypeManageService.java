package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.convert.SysDictTypeConvert;
import com.cloudai.system.dto.DictTypeSaveRequest;
import com.cloudai.system.entity.SysDictType;
import com.cloudai.system.mapper.SysDictDataMapper;
import com.cloudai.system.mapper.SysDictTypeMapper;
import com.cloudai.system.vo.SysDictTypeVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典类型管理（契约 2026-10-07-dict-api §2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDictTypeManageService {

    /** 错误码分段：3xxx system，字典域占用 3008-3012，3013 起归内置角色/菜单保护另案（契约 §5） */
    private static final int ERR_TYPE_NOT_FOUND = 3008;
    private static final int ERR_DICT_KEY_DUP = 3009;
    private static final int ERR_TYPE_HAS_DATA = 3011;

    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataMapper dictDataMapper;
    private final TranslationCacheService translationCacheService;

    /** 只读不加事务注解 */
    public PageResult<SysDictTypeVo> pageList(PageQuery query) {
        IPage<SysDictType> page = dictTypeMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()));
        List<SysDictTypeVo> rows = page.getRecords().stream().map(SysDictTypeConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    public Long save(DictTypeSaveRequest req) {
        assertDictNameValid(req.getDictName());
        assertDictKeyValid(req.getDictKey());
        assertDictKeyFree(req.getDictKey(), null);
        SysDictType type = new SysDictType();
        type.setDictName(req.getDictName());
        type.setDictKey(req.getDictKey());
        type.setStatus(req.getStatus() == null ? SysDictType.StatusEnum.NORMAL.getCode() : req.getStatus());
        auditCreate(type);
        try {
            dictTypeMapper.save(type);
        } catch (DuplicateKeyException e) {
            // 逻辑删除墓碑仍占用 uk_dict_key，countByDictKey 查重看不到——捕获兜底转业务码
            log.error("字典键唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_DICT_KEY_DUP, "字典键已存在: " + req.getDictKey());
        }
        evictDictTransCache(req.getDictKey());
        return type.getId();
    }

    public void update(DictTypeSaveRequest req) {
        SysDictType current = requireType(req.getId());
        // 空白串拦截；null 不拦——部分更新语义，与 role 的 roleKey 口径一致
        if (req.getDictName() != null && req.getDictName().isBlank()) {
            throw new BusinessException("字典名称不能为空");
        }
        if (req.getDictKey() != null && req.getDictKey().isBlank()) {
            throw new BusinessException("字典键不能为空");
        }
        if (req.getDictKey() != null) {
            assertDictKeyFree(req.getDictKey(), req.getId());
        }
        SysDictType type = new SysDictType();
        type.setId(req.getId());
        type.setDictName(req.getDictName());
        type.setDictKey(req.getDictKey());
        type.setStatus(req.getStatus());
        type.setUpdateBy(SecurityUtils.currentAccount());
        type.setUpdateTime(LocalDateTime.now());
        try {
            dictTypeMapper.update(type);
        } catch (DuplicateKeyException e) {
            log.error("字典键唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_DICT_KEY_DUP, "字典键已存在: " + req.getDictKey());
        }
        // 改键/改状态都影响消费口径：旧键必失效，改键时新键一并失效（设计 §4.2 两键）
        evictDictTransCache(current.getDictKey());
        if (req.getDictKey() != null && !req.getDictKey().equals(current.getDictKey())) {
            evictDictTransCache(req.getDictKey());
        }
    }

    /** 单表单语句无 @Transactional；禁删有项类型（3011），不做级联逻辑删（设计 D5），count-then-delete 的 TOCTOU 窗口接受 */
    public void delete(Long id) {
        SysDictType type = requireType(id);
        Long dataCount = dictDataMapper.countByTypeId(id);
        if (dataCount > 0) {
            throw new BusinessException(ERR_TYPE_HAS_DATA, "该字典类型下存在字典项，先删除字典项");
        }
        dictTypeMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        evictDictTransCache(type.getDictKey());
    }

    /** 翻译缓存失效（防御性 DEL，新键无缓存也可 DEL）：失败仅 log.error 不抛——DB 已提交，TTL 兜底 */
    private void evictDictTransCache(String dictKey) {
        try {
            translationCacheService.deleteDict(dictKey);
        } catch (Exception e) {
            log.error("dict trans cache evict failed, dictKey={}", dictKey, e);
        }
    }

    private void assertDictNameValid(String dictName) {
        if (dictName == null || dictName.isBlank()) {
            throw new BusinessException("字典名称不能为空");
        }
    }

    private void assertDictKeyValid(String dictKey) {
        if (dictKey == null || dictKey.isBlank()) {
            throw new BusinessException("字典键不能为空");
        }
    }

    /** 预检（编辑时排除自身）；逻辑删除墓碑仍占 uk_dict_key，并发/墓碑场景由 DuplicateKey 兜底 */
    private void assertDictKeyFree(String dictKey, Long excludeId) {
        Long count = dictTypeMapper.countByDictKey(dictKey, excludeId);
        if (count > 0) {
            throw new BusinessException(ERR_DICT_KEY_DUP, "字典键已存在: " + dictKey);
        }
    }

    /** 手写 SQL 无自动填充：审计四值显式构造，插入时 update 值 = create 值 */
    private void auditCreate(SysDictType type) {
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        type.setCreateBy(operator);
        type.setCreateTime(now);
        type.setUpdateBy(operator);
        type.setUpdateTime(now);
    }

    private SysDictType requireType(Long id) {
        SysDictType type = dictTypeMapper.findById(id);
        if (type == null) {
            throw new BusinessException(ERR_TYPE_NOT_FOUND, "字典类型不存在: " + id);
        }
        return type;
    }
}
