<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What was done to the business, newest first — read the way an auditor reads it: who, when, what,
  and what came of it.

  This is a different chain from the AI audit beside it. The rows here are business writes, including
  the ones no AI took part in; the AI audit records what a tool call came to. Nothing joins the two,
  and the page does not pretend otherwise.

  Two things this page deliberately does not have. **No filters**: this face takes a window and
  nothing else, so offering a caller or a period would return a list that looks filtered and is not —
  the same reason the audit page does not offer `agentId`/`orgId`, which that runtime *refuses*
  rather than ignores. And **no columns for what the runtime does not answer**: the request body, the
  field-level diff, the feature names and the two hash columns are declared by the frozen object and
  filled by nobody here, so the page draws the ten fields it is given.

  对**业务**做了什么，最新的在前 —— 按**审计人员读它的方式**读：**谁**、**什么时候**、**做了什么**、
  **结果**如何。

  它读的是与旁边那条 AI 审计**不同**的链。这里的行是**业务写**，含没有 AI 参与的那些；AI 审计记的是
  「一次工具调用最后怎么了」。没有任何东西把两者连起来，页面也不假装有。

  有两样东西这一页**刻意没有**。**不打筛选**：这条面只收一个窗口、别的都不收，故提供一个调用方或一段
  时间，回出来的会是一份**看着像筛过、其实没有**的列表 —— 与审计页不提供 `agentId`/`orgId` 是同一条
  理由（那个运行时是**拒绝**它们、不是忽略）。**不为运行时不答的东西画列**：请求正文、字段级差异、功能名
  与两个哈希列都是**冻结对象声明了、而这里没有东西填**的，故页面只画**给到它的那十个字段**。
-->
<template>
  <ContentWrap :title="t('keelbase.opAudit.title')">
    <template #header>
      <el-tag v-if="total" size="small" effect="plain">{{ t('keelbase.opAudit.total', { n: total }) }}</el-tag>
      <el-button class="ml-auto" size="small" :loading="loading" @click="load">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <!-- Said on the page, not left to be noticed: there is nothing here to filter by. -->
    <!-- 写在页面上、而不是留给读者自己发现：这里没有可按之筛选的东西。 -->
    <div class="note">{{ t('keelbase.opAudit.noFilter') }}</div>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="row-detail">
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWho') }}</span>
              <span>{{ callerName(row.userId) }} <span v-if="row.userId" class="muted">#{{ row.userId }}</span></span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colTime') }}</span>
              <span>{{ timeText(row.createdAt) }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWhat') }}</span>
              <span>{{ actionText(row.action) }} · {{ row.method || '—' }} {{ row.path || '' }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colResult') }}</span>
              <span>
                <el-tag :type="statusTagType(row.statusCode)" size="small">{{ row.statusCode ?? '—' }}</el-tag>
                <span class="muted ml-8px">{{ resultText(row) }}</span>
              </span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.opAudit.colTarget') }}</span>
              <span>{{ row.targetId ?? '—' }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.opAudit.colSource') }}</span>
              <span>{{ row.ip || '—' }}<span v-if="row.userAgent" class="muted"> · {{ row.userAgent }}</span></span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colId') }}</span>
              <span>#{{ row.id }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colTime')" width="175">
        <template #default="{ row }">{{ timeText(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colWho')" width="170">
        <template #default="{ row }">
          <div>{{ callerName(row.userId) }}</div>
          <!-- 没有 id 的行**不画那一行**：`#—` 读起来像一个坏掉的 id，而不是「这次没人可挂」。 -->
          <div v-if="row.userId" class="muted">#{{ row.userId }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colWhat')" min-width="240">
        <template #default="{ row }">
          <div>{{ actionText(row.action) }}</div>
          <div class="muted">{{ row.method || '—' }} {{ row.path || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colResult')" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.statusCode)" size="small">{{ row.statusCode ?? '—' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.opAudit.colTarget')" width="130">
        <template #default="{ row }">{{ row.targetId ?? '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.opAudit.colSource')" width="150">
        <template #default="{ row }"><span class="muted">{{ row.ip || '—' }}</span></template>
      </el-table-column>
    </el-table>

    <Pagination
      v-if="total > 0"
      v-model:page="page"
      v-model:limit="limit"
      :total="total"
      @pagination="load"
    />
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import Pagination from '@/components/Pagination/index.vue'
import { operationAuditApi, type OperationAuditRow } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { opActionLabelKey, statusTagType } from '@/keelbase/labels'
import { resolveCallerNames } from '@/keelbase/callerNames'
import { timeText } from '@/keelbase/time'

defineOptions({ name: 'KeelbaseGovernanceOperationAudit' })

const { t } = useI18n()

const rows = ref<OperationAuditRow[]>([])
const total = ref(0)
const page = ref(1)
const limit = ref(20)
const loading = ref(false)
const error = ref('')

/** Caller names for the ids on screen, filled in after the rows land. */
/** 屏幕上那些 id 的调用方姓名，在行落地之后补上。 */
const names = ref<Record<string, string>>({})

function callerName(id: string | null | undefined): string {
  if (!id) return '—'
  return names.value[String(id)] ?? String(id)
}

function actionText(action: string | null | undefined): string {
  const key = opActionLabelKey(action)
  return key ? t(key) : (action || '—')
}

/** One word for what the row came to, alongside the code itself. */
/** 给这一行的**结果**配一个词，与码本身并列。 */
function resultText(row: OperationAuditRow): string {
  if (row.statusCode === null || row.statusCode === undefined) return '—'
  return row.statusCode >= 400 ? t('keelbase.opAudit.failed') : t('keelbase.opAudit.ok')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await operationAuditApi.list(page.value, limit.value)
    rows.value = res.items
    total.value = res.total
    // Names are an enrichment: the rows are already rendered, and an id stands in until the host's
    // directory answers. The lookup never rejects, so a failure here leaves the ids in place.
    // 姓名是**补上去的**：行已经渲染，在宿主的名录答复之前由 id 顶着。那次查询从不 reject。
    const resolved = await resolveCallerNames(res.items.map((r) => r.userId))
    names.value = { ...names.value, ...resolved }
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.note {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.row-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 8px 8px 40px;
}
.d-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-size: 13px;
}
.d-key {
  min-width: 72px;
  color: var(--el-text-color-secondary);
}
</style>
