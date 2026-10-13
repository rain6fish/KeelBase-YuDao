<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What the AI is waiting on this person for, and what it has already written on their behalf.

  The reference console also lists recent conversations here and offers a history drawer per row.
  Neither is drawn: this runtime has no route for a conversation list, and the drawer reads a
  business-object history it does not keep. What is drawn is what the runtime answers — the pending
  decisions and the effect ledger, both scoped to the caller by the server.

  A pending row whose mode is not `immediate` is not decidable here, and the page says where it
  belongs instead of showing a button that cannot work.

  这个人在等 AI 的哪一笔，以及 AI 已经替他写了什么。

  参照实现还会在这里列出最近会话、并为每一行给一个历史抽屉。两者都不画：本运行时没有会话列表这条
  路由，抽屉读的是一份它不保留的业务对象历史。画出来的是运行时答得出来的——待决的那些，以及副作用
  台账，两者的范围都由**服务端**按调用方定。

  `mode` 不是 `immediate` 的待决行**不能在这里裁决**，页面说的是它归哪儿，而不是给一个按不动的按钮。
-->
<template>
  <ContentWrap :title="t('keelbase.actionCenter.pendingTitle')">
    <template #header>
      <el-tag v-if="pending.length" size="small" type="warning" effect="plain">
        {{ t('keelbase.actionCenter.pendingCount', { n: pending.length }) }}
      </el-tag>
      <el-button class="ml-auto" size="small" :loading="pendingLoading" @click="loadPending">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <div v-if="pendingLoading && !pending.length" class="text-[var(--el-text-color-secondary)]">
      {{ t('keelbase.common.loading') }}
    </div>
    <el-empty v-else-if="!pending.length" :description="t('keelbase.actionCenter.pendingEmpty')" />
    <div v-else>
      <div v-for="c in pending" :key="c.token" class="row">
        <div class="row-main">
          <div class="row-head">
            <span class="font-weight-medium">{{ toolText(c.toolName) }}</span>
            <el-tag v-if="c.riskLevel" size="small" effect="plain">{{ c.riskLevel }}</el-tag>
            <el-tag v-if="c.mode !== 'immediate'" size="small" type="info" effect="plain">{{ c.mode }}</el-tag>
          </div>
          <!--
            A human-readable summary is a field the runtime sends as null: it has no presentation
            layer. The arguments are shown verbatim instead — they are what the call would carry,
            which is the honest substitute for a sentence about them.

            给人读的摘要是运行时**送 null** 的字段：它没有展示层。故改为原样显示参数——那是这次调用
            真会带的东西，也是那句摘要的诚实替代品。上面那句话把这块是**什么**说清楚，好让一块裸 JSON
            读起来是「这次调用会带的东西」，而不是一段没头没尾的文本。
          -->
          <div class="args-label">{{ t('keelbase.actionCenter.argsLabel') }}</div>
          <pre class="args">{{ formatArgs(c.arguments) }}</pre>
          <div class="row-meta">
            {{ timeText(c.createdAt) }}
            <template v-if="c.expiresAt"> · {{ t('keelbase.actionCenter.expiresAt', { at: timeText(c.expiresAt) }) }}</template>
          </div>
        </div>
        <div v-if="c.mode === 'immediate'" class="row-actions">
          <el-button size="small" type="primary" :loading="deciding === c.token" :disabled="deciding !== null" @click="decide(c, 'approve')">
            {{ t('keelbase.actionCenter.approve') }}
          </el-button>
          <el-button size="small" :loading="deciding === c.token" :disabled="deciding !== null" @click="decide(c, 'decline')">
            {{ t('keelbase.actionCenter.decline') }}
          </el-button>
        </div>
        <div v-else class="row-actions text-12px text-[var(--el-text-color-secondary)]">
          {{ t('keelbase.actionCenter.notDecidableHere') }}
        </div>
      </div>
    </div>
  </ContentWrap>

  <ContentWrap :title="t('keelbase.actionCenter.writesTitle')">
    <template #header>
      <el-tag v-if="total" size="small" effect="plain">{{ t('keelbase.actionCenter.writesTotal', { n: total }) }}</el-tag>
      <el-button class="ml-auto" size="small" :loading="effectsLoading" @click="loadEffects">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <div v-if="effectsError" class="text-[var(--el-color-danger)] mb-10px">{{ effectsError }}</div>
    <el-table v-loading="effectsLoading" :data="effects" stripe>
      <el-table-column :label="t('keelbase.effects.colTool')" width="220">
        <template #default="{ row }">{{ toolText(row.toolName) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colTarget')" width="240">
        <template #default="{ row }">{{ row.targetTitle || `#${row.resultId}` }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colResultType')" prop="resultType" width="150" />
      <el-table-column :label="t('keelbase.effects.colStatus')" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colTime')" width="190">
        <template #default="{ row }">{{ timeText(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colActions')" width="110" align="center">
        <template #default="{ row }">
          <!-- The server decides revocability, and the page believes it rather than re-deriving it. -->
          <!-- 可撤性由**服务端**定，页面信它，而不是自己重算一遍。 -->
          <el-button v-if="row.revocable" link type="danger" @click="confirmRevoke(row)">
            {{ t('keelbase.effects.revoke') }}
          </el-button>
          <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      v-if="total > 0"
      v-model:page="page"
      v-model:limit="limit"
      :total="total"
      @pagination="loadEffects"
    />
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ContentWrap } from '@/components/ContentWrap'
import Pagination from '@/components/Pagination/index.vue'
import { confirmationApi, effectApi, type ConfirmationItem, type EffectRow } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { toolLabelKey } from '@/keelbase/labels'

defineOptions({ name: 'KeelbaseGovernanceActionCenter' })

const { t } = useI18n()

/** The tool in the reader's language, falling back to the registered name. */
/** 工具名，用读者的语言；不认识就保留注册名。 */
function toolText(name: string | null | undefined): string {
  if (!name) return '—'
  const key = toolLabelKey(name)
  return key ? t(key) : name
}

const pending = ref<ConfirmationItem[]>([])
const pendingLoading = ref(false)
const deciding = ref<string | null>(null)

const effects = ref<EffectRow[]>([])
const total = ref(0)
const page = ref(1)
const limit = ref(20)
const effectsLoading = ref(false)
const effectsError = ref('')

function timeText(v: string | null | undefined): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function formatArgs(args: Record<string, unknown> | null): string {
  if (!args || Object.keys(args).length === 0) return '—'
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return '—'
  }
}

function statusLabel(status: string): string {
  if (status === 'revoked') return t('keelbase.effects.statusRevoked')
  return t('keelbase.effects.statusExecuted')
}

function statusTag(status: string): 'success' | 'info' {
  return status === 'revoked' ? 'info' : 'success'
}

async function loadPending() {
  pendingLoading.value = true
  try {
    pending.value = await confirmationApi.mine('pending')
  } catch (e) {
    pending.value = []
    ElMessage.error(runtimeErrorMessage(e, t('keelbase.common.loadFailed')))
  } finally {
    pendingLoading.value = false
  }
}

async function loadEffects() {
  effectsLoading.value = true
  effectsError.value = ''
  try {
    const res = await effectApi.list(page.value, limit.value)
    effects.value = res.items
    total.value = res.total
  } catch (e) {
    effects.value = []
    total.value = 0
    effectsError.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    effectsLoading.value = false
  }
}

async function decide(item: ConfirmationItem, decision: 'approve' | 'decline') {
  deciding.value = item.token
  try {
    await confirmationApi.decide(item.token, decision)
    ElMessage.success(t(decision === 'approve' ? 'keelbase.actionCenter.approved' : 'keelbase.actionCenter.declined'))
    // An approval runs the write, so the ledger is refreshed alongside the pending list.
    // 批准会**真的执行**那笔写，故台账与待决列表一起刷新。
    await Promise.all([loadPending(), loadEffects()])
  } catch (e) {
    ElMessage.error(runtimeErrorMessage(e, t('keelbase.actionCenter.decideFailed')))
    await loadPending()
  } finally {
    deciding.value = null
  }
}

function confirmRevoke(row: EffectRow) {
  ElMessageBox.confirm(
    t('keelbase.effects.revokeConfirm', { title: row.targetTitle || `#${row.resultId}` }),
    t('keelbase.effects.revoke'),
    { type: 'warning' },
  )
    .then(async () => {
      try {
        await effectApi.revoke(row.id)
        ElMessage.success(t('keelbase.effects.revoked'))
        await loadEffects()
      } catch (e) {
        ElMessage.error(runtimeErrorMessage(e, t('keelbase.effects.revokeFailed')))
      }
    })
    .catch(() => undefined)
}

onMounted(() => {
  void loadPending()
  void loadEffects()
})
</script>

<style scoped>
.row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.row:last-child {
  border-bottom: none;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.row-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}
.row-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  align-items: center;
}
.args-label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.args {
  margin: 6px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
}
</style>
