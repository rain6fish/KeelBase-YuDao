<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The caller's own conversations, and the trace of whichever one is opened.

  What this page cannot show is stated on it rather than left to be noticed: this deployment records
  no conversation link on its audit and side-effect rows, so a tool call, a confirmation decision or
  an effect cannot be placed in a conversation's trace. The runtime says so itself — it appends a
  step of type `notice` saying exactly that — and that step is drawn as a disclosure rather than as
  one more step in the sequence, because reading it as a step would suggest the trace is complete.

  The list is the caller's own and the trace refuses another user's, both decided by the server; the
  page does not filter or re-check either.

  调用方自己的会话，以及打开的那一个的轨迹。

  **本页展示不了的东西写在页面上**，而不是留给读者自己发现：本部署在审计行与副作用行上**没有会话链接**，
  故一次工具调用、一次确认裁决、一条副作用，都**放不进**某个会话的轨迹。运行时**自己就这么说**——它追加
  一条 `type = notice` 的步、把这句话讲明——而那一步**作为披露呈现**、不是当作序列里的又一步，因为把它当
  一步读，会让人以为这条轨迹是完整的。

  列表是**调用方自己的**，轨迹**拒绝别人的**，两者都由服务端决定；页面既不筛也不复查。
-->
<template>
  <ContentWrap :title="t('keelbase.trace.title')">
    <template #header>
      <el-tag v-if="conversations.length" size="small" effect="plain">
        {{ t('keelbase.trace.count', { n: conversations.length }) }}
      </el-tag>
      <el-button class="ml-auto" size="small" :loading="loading" @click="load">{{ t('keelbase.common.refresh') }}</el-button>
    </template>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <el-empty v-if="!loading && !conversations.length" :description="t('keelbase.trace.empty')" />
    <el-table v-else v-loading="loading" :data="conversations" stripe @row-click="open">
      <el-table-column :label="t('keelbase.trace.colId')" prop="id" min-width="280" show-overflow-tooltip />
      <el-table-column :label="t('keelbase.trace.colModel')" width="220">
        <template #default="{ row }">{{ row.provider }} / {{ row.model }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.trace.colMessages')" width="120" align="center">
        <template #default="{ row }">{{ row.messages.length }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.trace.colLast')" width="200">
        <template #default="{ row }">{{ timeText(row.lastActivityAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.trace.colOpen')" width="110" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" :loading="opening === row.id" @click.stop="open(row)">
            {{ t('keelbase.trace.open') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>

  <ContentWrap v-if="trace" :title="t('keelbase.trace.stepsTitle')">
    <template #header>
      <span class="text-12px text-[var(--el-text-color-secondary)]">{{ trace.conversation.id }}</span>
      <el-button class="ml-auto" size="small" @click="trace = null">{{ t('keelbase.trace.close') }}</el-button>
    </template>
    <div v-if="traceError" class="text-[var(--el-color-danger)]">{{ traceError }}</div>
    <div v-else class="steps">
      <div v-for="s in trace.steps" :key="s.id" class="step" :class="{ notice: s.type === 'notice' }">
        <span class="step-time">{{ timeText(s.time) }}</span>
        <el-tag v-if="s.type !== 'notice'" size="small" effect="plain">{{ stepTypeText(s.type) }}</el-tag>
        <!-- A `notice` step is the runtime disclosing what it cannot place here, so it is drawn as a
             disclosure and not as one more thing that happened. -->
        <!-- 一条 `notice` 步，是运行时在**披露**它放不进这里的东西，故按披露画、不按「又发生了一件事」画。 -->
        <el-tag v-else size="small" type="warning">{{ t('keelbase.trace.noticeTag') }}</el-tag>
        <span class="step-content" :class="{ 'text-[var(--el-color-warning)]': s.type === 'notice' }">{{ s.content || '—' }}</span>
      </div>
    </div>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import { conversationApi, type ConversationData, type ConversationTrace } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { traceStepLabelKey } from '@/keelbase/labels'
import { timeText } from '@/keelbase/time'

defineOptions({ name: 'KeelbaseGovernanceConversationTrace' })

const { t } = useI18n()

/** The step's kind in the reader's words; an unknown type is shown as itself. */
/** 这一步的**种类**用读者的话说；不认识的类型原样显示。 */
function stepTypeText(type: string): string {
  const key = traceStepLabelKey(type)
  return key ? t(key) : type
}

const conversations = ref<ConversationData[]>([])
const loading = ref(false)
const error = ref('')

const trace = ref<ConversationTrace | null>(null)
const traceError = ref('')
const opening = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    conversations.value = await conversationApi.list()
  } catch (e) {
    conversations.value = []
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function open(row: ConversationData) {
  opening.value = row.id
  traceError.value = ''
  try {
    trace.value = await conversationApi.trace(row.id)
  } catch (e) {
    trace.value = null
    traceError.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    opening.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.step.notice {
  background: var(--el-color-warning-light-9);
}
.step-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  min-width: 150px;
}
.step-content {
  flex: 1;
  font-size: 13px;
  word-break: break-word;
}
</style>
