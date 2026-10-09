package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.domain.ApprovalEventMessage;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ProjectionRow;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ReconcileDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 投影 DAO 单测（mock JdbcTemplate，沿 starter DAO 测试先例）：三分支 upsert SQL 参数序与
 * ON DUPLICATE 列族断言（乱序矩阵核心——SUCCESS/FAILED 永不写 approval_status、TERMINAL 永不写
 * create_result/approval_id/pid，重投同值幂等空效果）+ 对账条件回写 + 游标分批 + 按键查。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalProjectionDaoTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 15, 0, 0);

    // ---- 三分支 upsert：SQL 参数序 ----

    @Test
    void applyCreateSuccess_bindsParamsInOrder() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);

        dao.applyCreateSuccess(event(), "bpmn-event", now);

        verify(jdbcTemplate).update(eq(ApprovalProjectionDao.SQL_APPLY_CREATE_SUCCESS),
                eq("leave"), eq("5"), eq(12L), eq("pid-9"),
                eq("bpmn-event"), eq("bpmn-event"), eq(Timestamp.valueOf(now)));
    }

    @Test
    void applyCreateFailed_bindsParamsInOrder() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);

        dao.applyCreateFailed(event(), "bpmn-event", now);

        verify(jdbcTemplate).update(eq(ApprovalProjectionDao.SQL_APPLY_CREATE_FAILED),
                eq("leave"), eq("5"), eq("bpmn-event"), eq("bpmn-event"), eq(Timestamp.valueOf(now)));
    }

    @Test
    void applyTerminal_bindsParamsInOrder() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);

        dao.applyTerminal(event(), 3, "bpmn-event", now);

        verify(jdbcTemplate).update(eq(ApprovalProjectionDao.SQL_APPLY_TERMINAL),
                eq("leave"), eq("5"), eq(3), eq("bpmn-event"), eq("bpmn-event"), eq(Timestamp.valueOf(now)));
    }

    // ---- 乱序矩阵：ON DUPLICATE 列族互斥（设计 D4 单调不变式的 SQL 语义） ----

    @Test
    void upsertColumnFamilies_maintainMonotonicInvariants() {
        // CREATE_RESULT/SUCCESS：补 approval_id/pid/create_result=1——永不写 approval_status
        //（TERMINAL 先到已置终态时，SUCCESS 后到补达不回退 approval_status）
        String success = onDuplicateClause(ApprovalProjectionDao.SQL_APPLY_CREATE_SUCCESS);
        assertThat(success)
                .contains("approval_id = VALUES(approval_id)")
                .contains("process_instance_id = VALUES(process_instance_id)")
                .contains("create_result = 1")
                .doesNotContain("approval_status");

        // CREATE_RESULT/FAILED：只写 create_result=2——永不写 approval_status/approval_id
        String failed = onDuplicateClause(ApprovalProjectionDao.SQL_APPLY_CREATE_FAILED);
        assertThat(failed)
                .contains("create_result = 2")
                .doesNotContain("approval_status")
                .doesNotContain("approval_id");

        // TERMINAL：只写 approval_status——永不写 create_result/approval_id/pid
        //（乱序先到建行 create_result=0，SUCCESS 后到补；重投同值幂等空效果）
        String terminal = onDuplicateClause(ApprovalProjectionDao.SQL_APPLY_TERMINAL);
        assertThat(terminal)
                .contains("approval_status = VALUES(approval_status)")
                .doesNotContain("create_result")
                .doesNotContain("approval_id")
                .doesNotContain("process_instance_id");

        // 三分支 INSERT 语句均含 uk_business upsert 语义依据
        assertThat(ApprovalProjectionDao.SQL_APPLY_CREATE_SUCCESS).contains("ON DUPLICATE KEY UPDATE");
        assertThat(ApprovalProjectionDao.SQL_APPLY_CREATE_FAILED).contains("ON DUPLICATE KEY UPDATE");
        assertThat(ApprovalProjectionDao.SQL_APPLY_TERMINAL).contains("ON DUPLICATE KEY UPDATE");
    }

    // ---- 对账回写：条件写幂等三防 ----

    @Test
    void applyReconcile_bindsParamsAndGuards() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);
        ReconcileDiff diff = new ReconcileDiff("leave", "5", 1, 12L, "pid-9");

        dao.applyReconcile(diff, "approval-reconcile", now);

        verify(jdbcTemplate).update(eq(ApprovalProjectionDao.SQL_APPLY_RECONCILE),
                eq(1), eq(12L), eq("pid-9"), eq("approval-reconcile"), eq(Timestamp.valueOf(now)),
                eq("leave"), eq("5"), eq(1));
        String sql = ApprovalProjectionDao.SQL_APPLY_RECONCILE;
        // create_result=2 失败行永不触碰（单向不回退）+ 已收敛行零更新（无审计扰动）
        assertThat(sql).contains("AND create_result <> 2");
        assertThat(sql).contains("AND (approval_status <> ? OR create_result = 0)");
        // create_result=0 行补发起成功三件套（IFNULL 保留已回填值，重放无害）
        assertThat(sql).contains("create_result = IF(create_result = 0, 1, create_result)");
        assertThat(sql).contains("approval_id = IFNULL(approval_id, ?)");
        assertThat(sql).contains("process_instance_id = IFNULL(process_instance_id, ?)");
    }

    // ---- 查询面：游标分批 + 按键查 ----

    @Test
    void listActive_cursorAndLimitBound() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                eq(900L), eq(100))).thenReturn(List.of());

        dao.listActive(900L, 100);

        verify(jdbcTemplate).query(eq(ApprovalProjectionDao.SQL_LIST_ACTIVE),
                any(org.springframework.jdbc.core.RowMapper.class), eq(900L), eq(100));
        assertThat(ApprovalProjectionDao.SQL_LIST_ACTIVE)
                .contains("approval_status = 0")
                .contains("create_result <> 2")
                .contains("id > ?")
                .contains("LIMIT ?");
    }

    @Test
    void findByBusiness_expandsKeyPlaceholders() {
        ApprovalProjectionDao dao = new ApprovalProjectionDao(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of(row(1L)));

        dao.findByBusiness("leave", List.of("5", "7"));

        verify(jdbcTemplate).query(eq(ApprovalProjectionDao.SQL_FIND_BY_BUSINESS_PREFIX + "?,?)"),
                any(org.springframework.jdbc.core.RowMapper.class),
                aryEq(new Object[]{"leave", "5", "7"}));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    // ---- 脚手架 ----

    /** 截取 ON DUPLICATE KEY UPDATE 子句（列族断言面） */
    private String onDuplicateClause(String sql) {
        int idx = sql.indexOf("ON DUPLICATE KEY UPDATE");
        assertThat(idx).as("upsert 语句必须含 ON DUPLICATE KEY UPDATE").isGreaterThan(0);
        return sql.substring(idx);
    }

    private ApprovalEventMessage event() {
        ApprovalEventMessage event = new ApprovalEventMessage();
        event.setEventType("CREATE_RESULT");
        event.setBusinessType("leave");
        event.setBusinessKey("5");
        event.setApprovalId("12");
        event.setProcessInstanceId("pid-9");
        event.setResult("SUCCESS");
        event.setOccurredAt("2026-10-09 15:00:00");
        return event;
    }

    private ProjectionRow row(long id) {
        return new ProjectionRow(id, "leave", String.valueOf(id + 100), null, 0, 0);
    }
}
