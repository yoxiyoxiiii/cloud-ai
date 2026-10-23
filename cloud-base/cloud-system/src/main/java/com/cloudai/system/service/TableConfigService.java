package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.convert.TableConfigConvert;
import com.cloudai.system.dto.TableConfigSaveRequest;
import com.cloudai.system.entity.SysTableConfig;
import com.cloudai.system.mapper.SysTableConfigMapper;
import com.cloudai.system.vo.TableConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户表格列配置服务（契约 2026-10-23-table-config-api §2 三端点）：读本人配置（无行 null）/
 * upsert 盲存 / 物理删（恢复默认，幂等）。
 * 盲存口径：只校验格式与上限，不理解列含义（不校验 key 是否为某页面真实列）、
 * 不维护 tableKey 注册表——页面演进零契约联动。
 * 并发口径：首次保存撞 uk 由 catch DuplicateKeyException 幂等收敛为 update——
 * 个人偏好整体覆盖语义下并发冲突不是错误（last write wins），与「撞唯一键转业务码」
 * 的常规兜底语义差异是刻意的（本域并发双写同值无害）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableConfigService {

    /** tableKey 字符集 [A-Za-z0-9:-]、长度 1-64（契约 §1） */
    private static final Pattern TABLE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:-]{1,64}$");

    /** 配置项 key 字符集 [A-Za-z0-9_]、长度 1-32（契约 §1：列标识=VO 字段名约定） */
    private static final Pattern COLUMN_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    /** 列数上限（契约 §2.2 校验链） */
    private static final int COLUMN_COUNT_MAX = 50;

    /** config 序列化总长上限（= DDL 列宽 VARCHAR(4000)，防超列宽 DB 500） */
    private static final int CONFIG_JSON_MAX = 4000;

    /** 自定义列宽下限 px（契约 §2.2） */
    private static final int WIDTH_MIN = 40;

    /** 自定义列宽上限 px（契约 §2.2） */
    private static final int WIDTH_MAX = 1000;

    private static final String MSG_BAD_TABLE_KEY = "表格标识格式无效";
    private static final String MSG_BAD_CONFIG = "列配置格式无效";

    private final SysTableConfigMapper configMapper;

    /** 查询本人配置（契约 §2.1）：无行返回 null=页面默认列序（非错误、不建默认行）；只读不加事务 */
    public TableConfigVo findMyConfig(String tableKey) {
        validateTableKey(tableKey);
        Long userId = requireUserId();
        SysTableConfig row = configMapper.findConfig(userId, tableKey);
        return TableConfigConvert.toVo(row == null ? null : row.getConfig());
    }

    /** 保存本人配置（契约 §2.2）：校验链 → JSON 串 → uk 判存 upsert；单语句方法不加事务（语句级原子 + uk 兜底） */
    public void saveMyConfig(String tableKey, TableConfigSaveRequest req) {
        validateTableKey(tableKey);
        Long userId = requireUserId();
        String configJson = buildValidatedConfig(req);
        upsert(userId, tableKey, configJson);
    }

    /** 恢复默认（契约 §2.3）：物理删行；行不存在同样成功（幂等）；单语句不加事务 */
    public void deleteMyConfig(String tableKey) {
        validateTableKey(tableKey);
        Long userId = requireUserId();
        configMapper.deleteConfig(userId, tableKey);
    }

    /** 校验 tableKey 字符集与长度 → 1002「表格标识格式无效」（三端点共用前置） */
    private void validateTableKey(String tableKey) {
        if (tableKey == null || !TABLE_KEY_PATTERN.matcher(tableKey).matches()) {
            throw new BusinessException(MSG_BAD_TABLE_KEY);
        }
    }

    /** 登录态取 userId：端点不收前端传参（防改他人配置）。网关鉴权前置保证登录态，
     * 无登录态理论不可达——防御性兜底 1002（异常路径留痕走全局处理器 warn 日志） */
    private Long requireUserId() {
        LoginUser user = SecurityUtils.currentUser();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException("登录态缺失");
        }
        return user.getUserId();
    }

    /** 校验链 + 序列化（契约 §2.2）：结构 → 总长 → 逐项 → 列数，任一不过 1002「列配置格式无效」。
     * 总长检查先于逐项/列数：超大 payload 快速拒绝（列数 51+ 的报错归属见两分支文案，同码同段文案前缀）。 */
    private String buildValidatedConfig(TableConfigSaveRequest req) {
        if (req == null || req.getColumns() == null) {
            throw new BusinessException(MSG_BAD_CONFIG);
        }
        String configJson = TableConfigConvert.toConfigJson(req);
        if (configJson.length() > CONFIG_JSON_MAX) {
            throw new BusinessException(MSG_BAD_CONFIG + "：序列化长度超上限 " + CONFIG_JSON_MAX);
        }
        validateColumns(req.getColumns());
        return configJson;
    }

    private void validateColumns(List<TableConfigSaveRequest.TableColumnConfigDto> columns) {
        if (columns.size() > COLUMN_COUNT_MAX) {
            throw new BusinessException(MSG_BAD_CONFIG + "：列数超上限 " + COLUMN_COUNT_MAX);
        }
        for (int i = 0; i < columns.size(); i++) {
            validateColumnItem(i + 1, columns.get(i));
        }
    }

    private void validateColumnItem(int ordinal, TableConfigSaveRequest.TableColumnConfigDto item) {
        if (item == null) {
            throw new BusinessException(MSG_BAD_CONFIG + "：第" + ordinal + "项为空");
        }
        if (item.getKey() == null || !COLUMN_KEY_PATTERN.matcher(item.getKey()).matches()) {
            throw new BusinessException(MSG_BAD_CONFIG + "：第" + ordinal + "项列标识格式无效");
        }
        if (item.getVisible() == null) {
            throw new BusinessException(MSG_BAD_CONFIG + "：第" + ordinal + "项 visible 缺失");
        }
        if (item.getWidth() != null && (item.getWidth() < WIDTH_MIN || item.getWidth() > WIDTH_MAX)) {
            throw new BusinessException(MSG_BAD_CONFIG + "：第" + ordinal + "项列宽须在 " + WIDTH_MIN + "-" + WIDTH_MAX);
        }
    }

    /** uk 判存 upsert：无行 insert、有行 update；并发首次保存撞 uk（TOCTOU）幂等收敛为 update 不报错 */
    private void upsert(Long userId, String tableKey, String configJson) {
        SysTableConfig existing = configMapper.findConfig(userId, tableKey);
        SysTableConfig row = new SysTableConfig();
        row.setUserId(userId);
        row.setTableKey(tableKey);
        row.setConfig(configJson);
        if (existing != null) {
            configMapper.updateConfig(row);
            return;
        }
        try {
            configMapper.saveConfig(row);
        } catch (DuplicateKeyException e) {
            log.error("表格配置唯一键冲突（并发首次保存，转 update 幂等收敛）：userId={}, tableKey={}",
                    userId, tableKey, e);
            configMapper.updateConfig(row);
        }
    }
}
