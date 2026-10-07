package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.convert.SysDictDataConvert;
import com.cloudai.system.dto.DictDataSaveRequest;
import com.cloudai.system.entity.SysDictData;
import com.cloudai.system.entity.SysDictType;
import com.cloudai.system.mapper.SysDictDataMapper;
import com.cloudai.system.mapper.SysDictTypeMapper;
import com.cloudai.system.vo.SysDictDataVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典项管理（契约 2026-10-07-dict-api §3）。项操作不校验所属类型存活（PUT/DELETE 只查项自身，
 * 可治理"类型已死项未清"的遗留数据——契约宽松语义 §6.7）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysDictDataManageService {

    /** 错误码分段：3xxx system，字典域占用 3008-3012，3013 起归内置角色/菜单保护另案（契约 §5） */
    private static final int ERR_TYPE_NOT_FOUND = 3008;
    private static final int ERR_DATA_NOT_FOUND = 3010;
    private static final int ERR_VALUE_DUP = 3012;

    private final SysDictDataMapper dictDataMapper;
    private final SysDictTypeMapper dictTypeMapper;

    /** typeId 必填（缺失/空 1002）且类型须存在（从严——页面联动需要明确 3008 而非静默空表，契约 §3.1 设计注） */
    public PageResult<SysDictDataVo> pageList(Long typeId, PageQuery query) {
        if (typeId == null) {
            throw new BusinessException("类型不能为空");
        }
        requireType(typeId);
        IPage<SysDictData> page = dictDataMapper.pageListByTypeId(
                new Page<>(query.getPageNum(), query.getPageSize()), typeId);
        List<SysDictDataVo> rows = page.getRecords().stream().map(SysDictDataConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    public Long save(DictDataSaveRequest req) {
        if (req.getTypeId() == null) {
            throw new BusinessException("类型不能为空");
        }
        assertLabelValid(req.getLabel());
        assertValueValid(req.getValue());
        requireType(req.getTypeId());
        assertValueFree(req.getTypeId(), req.getValue(), null);
        SysDictData data = new SysDictData();
        data.setDictTypeId(req.getTypeId());
        data.setLabel(req.getLabel());
        data.setValue(req.getValue());
        data.setSort(req.getSort());
        data.setStatus(req.getStatus() == null ? SysDictData.StatusEnum.NORMAL.getCode() : req.getStatus());
        auditCreate(data);
        try {
            dictDataMapper.save(data);
        } catch (DuplicateKeyException e) {
            // 逻辑删除墓碑仍占用 uk_type_value，countByTypeValue 查重看不到——捕获兜底转业务码
            log.error("字典项值唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_VALUE_DUP, "该类型下字典项值已存在: " + req.getValue());
        }
        return data.getId();
    }

    public void update(DictDataSaveRequest req) {
        SysDictData current = requireData(req.getId());
        // 空白串拦截；null 不拦——部分更新语义
        if (req.getLabel() != null && req.getLabel().isBlank()) {
            throw new BusinessException("标签不能为空");
        }
        if (req.getValue() != null && req.getValue().isBlank()) {
            throw new BusinessException("字典值不能为空");
        }
        // typeId 变更时校验目标类型存在（项可在类型间迁移）；生效 typeId = 本次传入 ?? 库中现值
        if (req.getTypeId() != null) {
            requireType(req.getTypeId());
        }
        Long effectiveTypeId = req.getTypeId() != null ? req.getTypeId() : current.getDictTypeId();
        String effectiveValue = req.getValue() != null ? req.getValue() : current.getValue();
        assertValueFree(effectiveTypeId, effectiveValue, req.getId());
        SysDictData data = new SysDictData();
        data.setId(req.getId());
        data.setDictTypeId(req.getTypeId());
        data.setLabel(req.getLabel());
        data.setValue(req.getValue());
        data.setSort(req.getSort());
        data.setStatus(req.getStatus());
        data.setUpdateBy(SecurityUtils.currentAccount());
        data.setUpdateTime(LocalDateTime.now());
        try {
            dictDataMapper.update(data);
        } catch (DuplicateKeyException e) {
            log.error("字典项值唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_VALUE_DUP, "该类型下字典项值已存在: " + effectiveValue);
        }
    }

    /** 单表单语句无 @Transactional；不校验所属类型是否存活（契约 §3.4） */
    public void delete(Long id) {
        requireData(id);
        dictDataMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    private void assertLabelValid(String label) {
        if (label == null || label.isBlank()) {
            throw new BusinessException("标签不能为空");
        }
    }

    private void assertValueValid(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("字典值不能为空");
        }
    }

    /** 预检（编辑时排除自身，按生效 typeId/value 口径）；墓碑/并发场景由 DuplicateKey 兜底 */
    private void assertValueFree(Long typeId, String value, Long excludeId) {
        Long count = dictDataMapper.countByTypeValue(typeId, value, excludeId);
        if (count > 0) {
            throw new BusinessException(ERR_VALUE_DUP, "该类型下字典项值已存在: " + value);
        }
    }

    /** 手写 SQL 无自动填充：审计四值显式构造，插入时 update 值 = create 值 */
    private void auditCreate(SysDictData data) {
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        data.setCreateBy(operator);
        data.setCreateTime(now);
        data.setUpdateBy(operator);
        data.setUpdateTime(now);
    }

    private SysDictData requireData(Long id) {
        SysDictData data = dictDataMapper.findById(id);
        if (data == null) {
            throw new BusinessException(ERR_DATA_NOT_FOUND, "字典项不存在: " + id);
        }
        return data;
    }

    private SysDictType requireType(Long id) {
        SysDictType type = dictTypeMapper.findById(id);
        if (type == null) {
            throw new BusinessException(ERR_TYPE_NOT_FOUND, "字典类型不存在: " + id);
        }
        return type;
    }
}
