<script setup lang="ts">
/**
 * 死信详情弹窗（契约 2026-10-11-mq-dlq-governance-api §3.2）：列表行入口（id 驱动），
 * 弹窗内新拉 MqDlqDetailVo——**全字段**展示（MqDlqVo 17 字段 + body/bodyTruncated/createTime/updateTime）；
 * body 原文以 <pre> 等宽展示（契约原文，不做 JSON 美化重排——截断行重排会失真）；
 * bodyTruncated=true 时 el-alert 提示 60000 字节截断（设计 R4）；
 * 状态标签字典驱动（mq_dlq_status，降级链：字典 → 本地镜像 → 原值），tag 颜色按原 status 字段映射
 */
import { ref, watch } from 'vue'
import { getMqDlqDetail } from '../../../../api/mqDlq'
import { getDictItems } from '../../../../api/dict'
import {
  MQ_DLQ_STATUS_DICT_KEY,
  MQ_DLQ_STATUS_MAP,
  MQ_DLQ_STATUS_TAG,
  type DictItemVo,
  type MqDlqDetailVo,
  type MqDlqVo,
} from '../../../../types/api'

interface Props {
  modelValue: boolean
  /** 列表行（提供 id；展示以弹窗内新拉的 detail 为准——行数据可能已过期） */
  row?: MqDlqVo
}

const props = defineProps<Props>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const loading = ref(false)
const detail = ref<MqDlqDetailVo>()

/** 状态字典项（mq_dlq_status，契约 §2）：弹窗打开时随详情一并拉取；失败留空走本地降级 */
const statusDict = ref<DictItemVo[]>([])

/** 状态标签降级链：字典译文 → 本地镜像 → 原值 */
function statusLabelOf(status: string): string {
  return statusDict.value.find((i) => i.value === status)?.label ?? MQ_DLQ_STATUS_MAP[status] ?? status
}

/** 打开序号：快速关闭重开时在途异步结果作废（防串单，沿 LeaveDetailDialog 先例） */
let openSeq = 0

/** 打开时按行 id 拉详情（3038 等业务错已被拦截器统一 toast，详情区保持空态） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    const seq = ++openSeq
    detail.value = undefined
    statusDict.value = []
    if (!props.row) {
      return
    }
    loading.value = true
    const id = props.row.id
    void Promise.all([
      getMqDlqDetail(id).then((data) => {
        if (seq === openSeq) {
          detail.value = data
        }
      }),
      getDictItems(MQ_DLQ_STATUS_DICT_KEY)
        .then((items) => {
          if (seq === openSeq) {
            statusDict.value = items
          }
        })
        .catch(() => {
          // 拦截器已统一 toast；状态标签走本地降级 MQ_DLQ_STATUS_MAP
        }),
    ])
      .catch(() => {
        // 拦截器已统一 toast（如 3038 记录不存在），详情区保持空态
      })
      .finally(() => {
        if (seq === openSeq) {
          loading.value = false
        }
      })
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="死信详情"
    width="780px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="台账 ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="消息 ID">
            <span class="mono-text">{{ detail.msgId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="消息键（msgKey）">{{ detail.msgKey }}</el-descriptions-item>
          <el-descriptions-item label="事务流水号">{{ detail.txNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="原 topic">{{ detail.bornTopic }}</el-descriptions-item>
          <el-descriptions-item label="消息 tag">{{ detail.tag ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源消费组">{{ detail.bornGroup }}</el-descriptions-item>
          <el-descriptions-item label="watch 消费组">{{ detail.watchGroup }}</el-descriptions-item>
          <el-descriptions-item label="处置状态">
            <el-tag :type="MQ_DLQ_STATUS_TAG[detail.status] ?? 'info'">
              {{ statusLabelOf(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="重投次数">{{ detail.resendCount }}</el-descriptions-item>
          <el-descriptions-item label="死信时重试次数">{{ detail.reconsumeTimes }}</el-descriptions-item>
          <el-descriptions-item label="最近重投时间">{{ detail.lastResendTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="消息产生时间">{{ detail.bornTime }}</el-descriptions-item>
          <el-descriptions-item label="进死信时间">{{ detail.dlqTime }}</el-descriptions-item>
          <el-descriptions-item label="watch 落库时间">{{ detail.createTime }}</el-descriptions-item>
          <el-descriptions-item label="最近更新">{{ detail.updateTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="最近处置人">{{ detail.operateAccount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="最近处置时间">{{ detail.operateTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 截断提示（契约 §3.2 bodyTruncated / 设计 R4：60000 字节截断，原文不完整） -->
        <el-alert
          v-if="detail.bodyTruncated"
          class="body-truncated-tip"
          type="warning"
          :closable="false"
          show-icon
          title="消息体超长已被截断"
          description="该消息体超过存储上限（60000 字节），以下展示内容不完整。"
        />

        <div class="body-title">消息体原文</div>
        <pre class="dlq-body">{{ detail.body ?? '（空消息体）' }}</pre>
      </template>
      <el-empty v-else-if="!loading" description="暂无数据" />
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 截断提示与主体拉开间距 */
.body-truncated-tip {
  margin-top: 16px;
}

.body-title {
  margin: 16px 0 8px;
  font-weight: 600;
}

/* 消息体原文：等宽字体 + 滚动（长文不撑爆弹窗）；契约原文直出，不做 JSON 重排 */
.dlq-body {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-fill-color-light);
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.mono-text {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
