package com.cloudai.task.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务分页查询入参（契约 2026-11-10-task-center-api §3）：status/taskType/submitBy 三筛选项。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskPageQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    /** 状态筛选：0排队 1执行中 2成功 3失败 4已取消（null=不筛） */
    private Integer status;

    /** 类型筛选（null/空=不筛） */
    private String taskType;

    /** 提交人筛选（null/空=不筛） */
    private String submitBy;
}
