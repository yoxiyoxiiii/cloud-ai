<script setup lang="ts">
/**
 * 待办任务页（/bpmn/task，设计 D9 通用化改造）：el-tabs 两页签（待办 / 已办）+ 办理弹窗
 * 数据源 /bpmn/task/todo（契约 §2.1，assignee=当前登录人，不分页——契约现状）与
 * /bpmn/task/done（契约 §2.2，TaskDoneVo 增 endTime/approve/comment/approvalStatus 四字段）
 * 行操作「去处理」（契约 §1 待办跳转协议）：detailPath 非空 router.push(detailPath)
 * （业务页识别 query.approval 自动开详情——detailPath 未配/渲染失败 null 时隐藏按钮）
 * 办理成功后刷新两页签（待办消行 + 已办增行）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listDoneTasks, listTodoTasks } from '../../../api/bpmn'
import { APPROVAL_STATUS_MAP } from '../../../types/api'
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

const router = useRouter()
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

/** 去处理（契约 §1 待办跳转协议）：跳业务详情落点（detailPath 渲染值，如 /system/leave?approval=12） */
function handleGoDetail(task: TaskVo): void {
  if (task.detailPath) {
    void router.push(task.detailPath)
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
          <el-table-column label="标题" min-width="200">
            <template #default="{ row }">
              <span>{{ todoRowOf(row).title }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务类型" width="110">
            <!-- 配置表 join 必返非空（契约 §9），无降级链 -->
            <template #default="{ row }">{{ todoRowOf(row).businessTypeName }}</template>
          </el-table-column>
          <el-table-column label="申请人" min-width="100">
            <template #default="{ row }">
              <span>{{ todoRowOf(row).applyUserName ?? todoRowOf(row).applyUser }}</span>
            </template>
          </el-table-column>
          <el-table-column label="到达时间" min-width="160">
            <template #default="{ row }">{{ todoRowOf(row).createTime ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <!-- 去处理：detailPath 未配/渲染失败 null 时隐藏（契约 §1） -->
              <el-button
                v-if="todoRowOf(row).detailPath"
                link
                type="primary"
                @click="handleGoDetail(todoRowOf(row))"
                >去处理</el-button
              >
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
          <el-table-column label="标题" min-width="180">
            <template #default="{ row }">
              <span>{{ doneRowOf(row).title }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务类型" width="100">
            <!-- 配置表 join 必返非空（契约 §9），无降级链 -->
            <template #default="{ row }">{{ doneRowOf(row).businessTypeName }}</template>
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
              <!-- 结果 tag 按原字段 approve 取色（"true"/"false" 字符串，契约 §2.2） -->
              <el-tag v-if="doneRowOf(row).approve === 'true'" type="success">同意</el-tag>
              <el-tag v-else-if="doneRowOf(row).approve === 'false'" type="danger">拒绝</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="审批意见" min-width="140">
            <template #default="{ row }">{{ doneRowOf(row).comment ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="当前单状态" width="100">
            <!-- 原值-译文成对降级链（契约 §2.2 approvalStatus @DictTrans 源字段 + §9） -->
            <template #default="{ row }">{{ doneRowOf(row).approvalStatusLabel ?? APPROVAL_STATUS_MAP[doneRowOf(row).approvalStatus] ?? doneRowOf(row).approvalStatus }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <CompleteDialog v-model="completeDialogVisible" :task="completeTask" @success="handleCompleteSuccess" />
  </el-card>
</template>
