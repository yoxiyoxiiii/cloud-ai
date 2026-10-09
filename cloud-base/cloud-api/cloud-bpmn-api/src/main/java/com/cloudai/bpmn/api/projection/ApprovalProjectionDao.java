package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * approval_projection 表 SQL（JdbcTemplate 手写，沿 mq_tx_log/mq_consume_dedup 同款先例——设计 D3 取舍：
 * 投影表是框架表，api 模块不是 mybatis 宿主（无 @MapperScan），JdbcTemplate 零侵入宿主配置；
 * 写权归框架（本 DAO，事件消费/对账回写），业务侧读权走业务 mapper XML JOIN——SQL 两处分属两方职责）。
 * <p>upsert 单调不变式（契约 §1.3 / 设计 D4）：CREATE_RESULT 只写 create_result/approval_id/
 * process_instance_id（永不写 approval_status）；TERMINAL 只写 approval_status（永不写
 * create_result/approval_id/process_instance_id）；对账写 diff 列（create_result=2 失败行永不触碰）。
 * 两列族独立收敛，任意乱序/重投组合安全。</p>
 */
@RequiredArgsConstructor
public class ApprovalProjectionDao {

    private final JdbcTemplate jdbcTemplate;

    /** CREATE_RESULT/SUCCESS upsert（设计 D4 表行 1）：INSERT 分支 create_result=1/approval_status=0；
     *  ON DUPLICATE 只补 approval_id/process_instance_id/create_result=1 + 审计——不含 approval_status */
    static final String SQL_APPLY_CREATE_SUCCESS =
            "INSERT INTO approval_projection (business_type, business_key, approval_id, process_instance_id, "
                    + "create_result, approval_status, create_by, update_by, update_time) "
                    + "VALUES (?, ?, ?, ?, 1, 0, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "approval_id = VALUES(approval_id), "
                    + "process_instance_id = VALUES(process_instance_id), "
                    + "create_result = 1, "
                    + "update_by = VALUES(update_by), "
                    + "update_time = VALUES(update_time)";

    /** CREATE_RESULT/FAILED upsert（设计 D4 表行 2）：INSERT 分支 create_result=2/approval_status=0/
     *  approval_id=NULL；ON DUPLICATE 只写 create_result=2 + 审计——不含 approval_status/approval_id */
    static final String SQL_APPLY_CREATE_FAILED =
            "INSERT INTO approval_projection (business_type, business_key, create_result, approval_status, "
                    + "create_by, update_by, update_time) "
                    + "VALUES (?, ?, 2, 0, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "create_result = 2, "
                    + "update_by = VALUES(update_by), "
                    + "update_time = VALUES(update_time)";

    /** TERMINAL upsert（设计 D4 表行 3）：INSERT 分支 create_result=0（乱序先到建行，SUCCESS 后到补）；
     *  ON DUPLICATE 只写 approval_status + 审计——不含 create_result/approval_id/process_instance_id */
    static final String SQL_APPLY_TERMINAL =
            "INSERT INTO approval_projection (business_type, business_key, approval_status, "
                    + "create_by, update_by, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "approval_status = VALUES(approval_status), "
                    + "update_by = VALUES(update_by), "
                    + "update_time = VALUES(update_time)";

    /** 对账 diff 回写：approval_status 推进为平台真相 + create_result=0 行补发起成功三件套；
     *  WHERE 条件写幂等（已收敛行零更新无审计扰动）+ create_result=2 失败行永不触碰（单调不回退） */
    static final String SQL_APPLY_RECONCILE =
            "UPDATE approval_projection "
                    + "SET approval_status = ?, "
                    + "create_result = IF(create_result = 0, 1, create_result), "
                    + "approval_id = IFNULL(approval_id, ?), "
                    + "process_instance_id = IFNULL(process_instance_id, ?), "
                    + "update_by = ?, update_time = ? "
                    + "WHERE business_type = ? AND business_key = ? "
                    + "AND create_result <> 2 "
                    + "AND (approval_status <> ? OR create_result = 0)";

    /** 对账范围扫描（id 游标分批：cursor 前进与行是否改写无关，天然终止；idx_approval_status 命中） */
    static final String SQL_LIST_ACTIVE =
            "SELECT id, business_type, business_key, approval_id, create_result, approval_status "
                    + "FROM approval_projection "
                    + "WHERE approval_status = 0 AND create_result <> 2 AND id > ? "
                    + "ORDER BY id LIMIT ?";

    static final String SQL_FIND_BY_BUSINESS_PREFIX =
            "SELECT id, business_type, business_key, approval_id, create_result, approval_status "
                    + "FROM approval_projection WHERE business_type = ? AND business_key IN (";

    /** 投影行（对账读取面：游标 id + 列族现状，写走 upsert/applyReconcile） */
    public record ProjectionRow(Long id, String businessType, String businessKey,
                                Long approvalId, Integer createResult, Integer approvalStatus) {
    }

    private static final RowMapper<ProjectionRow> ROW_MAPPER = new RowMapper<>() {
        @Override
        public ProjectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ProjectionRow(rs.getLong("id"), rs.getString("business_type"),
                    rs.getString("business_key"), rs.getObject("approval_id") == null ? null : rs.getLong("approval_id"),
                    rs.getInt("create_result"), rs.getInt("approval_status"));
        }
    };

    /** 消费 CREATE_RESULT/SUCCESS 落投影（L2 业务 uk 幂等：ON DUPLICATE 重投同值空效果） */
    public int applyCreateSuccess(ApprovalEventMessage event, String operator, LocalDateTime updateTime) {
        return jdbcTemplate.update(SQL_APPLY_CREATE_SUCCESS, event.getBusinessType(), event.getBusinessKey(),
                Long.valueOf(event.getApprovalId()), event.getProcessInstanceId(),
                operator, operator, Timestamp.valueOf(updateTime));
    }

    /** 消费 CREATE_RESULT/FAILED 落投影（发起失败行：approval_id 永为 NULL，对外 status=4 由读侧派生） */
    public int applyCreateFailed(ApprovalEventMessage event, String operator, LocalDateTime updateTime) {
        return jdbcTemplate.update(SQL_APPLY_CREATE_FAILED, event.getBusinessType(), event.getBusinessKey(),
                operator, operator, Timestamp.valueOf(updateTime));
    }

    /** 消费 TERMINAL 落投影（乱序先到建行 create_result=0；approval_status 单调推进不回退） */
    public int applyTerminal(ApprovalEventMessage event, int terminalStatus,
                             String operator, LocalDateTime updateTime) {
        return jdbcTemplate.update(SQL_APPLY_TERMINAL, event.getBusinessType(), event.getBusinessKey(),
                terminalStatus, operator, operator, Timestamp.valueOf(updateTime));
    }

    /** 对账 diff 回写（未命中=已收敛/失败行，幂等空更新） */
    public int applyReconcile(ReconcileDiff diff, String operator, LocalDateTime updateTime) {
        return jdbcTemplate.update(SQL_APPLY_RECONCILE, diff.approvalStatus(), diff.approvalId(),
                diff.processInstanceId(), operator, Timestamp.valueOf(updateTime),
                diff.businessType(), diff.businessKey(), diff.approvalStatus());
    }

    /** 拉非终态行一批（approval_status=0 且非失败；游标 = 上一批末行 id） */
    public List<ProjectionRow> listActive(long afterId, int limit) {
        return jdbcTemplate.query(SQL_LIST_ACTIVE, ROW_MAPPER, afterId, limit);
    }

    /** 按业务键查投影行（撤销链路即时对账入口；空键集调用方跳过） */
    public List<ProjectionRow> findByBusiness(String businessType, List<String> businessKeys) {
        String placeholders = String.join(",", java.util.Collections.nCopies(businessKeys.size(), "?"));
        String sql = SQL_FIND_BY_BUSINESS_PREFIX + placeholders + ")";
        Object[] params = new Object[businessKeys.size() + 1];
        params[0] = businessType;
        for (int i = 0; i < businessKeys.size(); i++) {
            params[i + 1] = businessKeys.get(i);
        }
        return jdbcTemplate.query(sql, ROW_MAPPER, params);
    }

    /** 对账回写指令（diff 结果：目标 approval_status + create_result=0 行缺失补全的发起成功三件套） */
    public record ReconcileDiff(String businessType, String businessKey, int approvalStatus,
                                Long approvalId, String processInstanceId) {
    }
}
