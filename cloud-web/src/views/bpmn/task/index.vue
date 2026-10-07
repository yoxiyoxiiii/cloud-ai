<script setup lang="ts">
/**
 * 待办任务页（/bpmn/task，设计 D10）：el-tabs 两页签（待办 / 已办）+ 办理弹窗
 * 数据源 /bpmn/task/todo（契约 §3.1，assignee=当前登录人，不分页——契约现状）与
 * /bpmn/task/done（契约 §3.2，TaskDoneVo 增 endTime/approve/comment/leaveStatusLabel 四字段）
 * 办理成功后刷新两页签（待办消行 + 已办增行）
 */
import { onMounted, ref } from 'vue'
import { listDoneTasks, listTodoTasks } from '../../../api/bpmn'
import { LEAVE_STATUS_MAP, LEAVE_TYPE_MAP } from '../../../types/api'
import type { TaskDoneVo, TaskVo } from '../../../types/api'
import CompleteDialog from './components/CompleteDialog.vue'

/** 组件名必须显式固定 = pathToRouteName('/bpmn/task')（动态路由设计 D9 keep-alive 契约） */
defineOptions({ name: 'BpmnTask' })

/**
 * EP el-table-column 插槽 row 类型固定为 DefaultRow（Record<string, any>），
 * 全页断言集中此两处，模板/处理函数禁止再散落裸 as
 */
function todoRowOf(row: unknown): TaskVo {
  return row as TaskVo
}

function doneRowOf(row: unknown): TaskDoneVo {
  return row as TaskDoneVo
}

const activeTab = ref<'todo' | 'done'>('todo')
const todoLoading = ref(false)
const doneLoading = ref(false)
const todoRows = ref<TaskVo[]>([])
const doneRows = ref<TaskDoneVo[]>([])

const completeDialogVisible = ref(false)
const completeTask = ref<TaskVo>()

async function loadTodo(): Promise<void> {
  todoLoading.value = true
  try {
    todoRows.value = await listTodoTasks()
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    todoLoading.value = false
  }
}

async function loadDone(): Promise<void> {
  doneLoading.value = true
  try {
    doneRows.value = await listDoneTasks()
  } catch {
    // 拦截器已统一 toast，表格保持现状
  } finally {
    doneLoading.value = false
  }
}

function handleTabChange(tab: string | number): void {
  // 切换页签时刷新对应列表（量级小取新鲜数据；初次挂载只拉待办）
  if (tab === 'done') {
    void loadDone()
  } else {
    void loadTodo()
  }
}

function openComplete(task: TaskVo): void {
  completeTask.value = task
  completeDialogVisible.value = true
}

/** 办理成功：两页签都刷新（待办消行 + 已办增行） */
function handleCompleteSuccess(): void {
  void loadTodo()
  void loadDone()
}

onMounted(() => {
  void loadTodo()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <span>待办任务</span>
    </template>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <el-tab-pane label="待办任务" name="todo">
        <el-table v-loading="todoLoading" :data="todoRows" row-key="taskId">
          <el-table-column label="请假标题" min-width="200">
            <template #default="{ row }">
              <span>{{ todoRowOf(row).leaveTitle }}</span>
            </template>
          </el-table-column>
          <el-table-column label="请假类型" width="100">
            <!-- 原值-译文成对降级链（契约 §3.1 实现期修正注记 + §7，同我的申请页样板） -->
            <template #default="{ row }">{{ todoRowOf(row).leaveTypeLabel ?? LEAVE_TYPE_MAP[todoRowOf(row).leaveType] ?? todoRowOf(row).leaveType }}</template>
          </el-table-column>
          <el-table-column label="申请人" min-width="100">
            <template #default="{ row }">
              <span>{{ todoRowOf(row).applyUserName ?? todoRowOf(row).applyUser }}</span>
            </template>
          </el-table-column>
          <el-table-column label="到达时间" min-width="160">
            <template #default="{ row }">{{ todoRowOf(row).createTime ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button
                v-perms="'bpmn:task:complete'"
                link
                type="primary"
                @click="openComplete(todoRowOf(row))"
                >办理</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="已办任务" name="done">
        <el-table v-loading="doneLoading" :data="doneRows" row-key="taskId">
          <el-table-column label="请假标题" min-width="180">
            <template #default="{ row }">
              <span>{{ doneRowOf(row).leaveTitle }}</span>
            </template>
          </el-table-column>
          <el-table-column label="请假类型" width="90">
            <!-- 成对降级链同待办 tab（契约 §3.1 注记 + §7） -->
            <template #default="{ row }">{{ doneRowOf(row).leaveTypeLabel ?? LEAVE_TYPE_MAP[doneRowOf(row).leaveType] ?? doneRowOf(row).leaveType }}</template>
          </el-table-column>
          <el-table-column label="申请人" min-width="100">
            <template #default="{ row }">
              <span>{{ doneRowOf(row).applyUserName ?? doneRowOf(row).applyUser }}</span>
            </template>
          </el-table-column>
          <el-table-column label="办理时间" min-width="160">
            <template #default="{ row }">{{ doneRowOf(row).endTime ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="办理结果" width="90">
            <template #default="{ row }">
              <!-- 结果 tag 按原字段 approve 取色（"true"/"false" 字符串，契约 §3.2） -->
              <el-tag v-if="doneRowOf(row).approve === 'true'" type="success">同意</el-tag>
              <el-tag v-else-if="doneRowOf(row).approve === 'false'" type="danger">拒绝</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="审批意见" min-width="140">
            <template #default="{ row }">{{ doneRowOf(row).comment ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="当前单状态" width="100">
            <!-- 原值-译文成对降级链（契约 §3.2 leaveStatus 补列注记 + §7，同 LeaveVo status 样板） -->
            <template #default="{ row }">{{ doneRowOf(row).leaveStatusLabel ?? LEAVE_STATUS_MAP[doneRowOf(row).leaveStatus] ?? doneRowOf(row).leaveStatus }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <CompleteDialog v-model="completeDialogVisible" :task="completeTask" @success="handleCompleteSuccess" />
  </el-card>
</template>
