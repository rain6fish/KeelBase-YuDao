<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The audit trail, read the way an auditor reads it: who did what, when, and what came of it.

  The rows are the page. Each one is shown as a sentence a person can read — the caller's name (resolved
  from the host's own directory, because the runtime answers an id and nothing more about the person),
  the time, the action in plain words, and the result — and any row can be opened to lay the whole row
  out at once. Two things the earlier version did are undone here. The two result columns (`outcome` and
  `isError`) became one, because for a tool call they said the same thing twice and for a non-tool row
  only one of them was ever filled; and the hash chain, which is *evidence that the trail is intact* and
  not the trail itself, moved below the rows and folds shut.

  The chain still verifies on this page and its verdict still shows in the rows' header, because a broken
  chain is the one thing that outranks everything else here.

  审计轨，按**审计人员读它的方式**读：**谁**做了什么、**什么时候**、**结果**如何。

  那些**行**才是这一页。每一行都写成一句能读的话——调用方的**姓名**（从**宿主自己的名录**解析出来，
  因为运行时对这个人只答一个 id、别无其他）、时间、用人话写的动作、以及结果——而任何一行都能摊开、把
  整行一次看全。此前做过的两件事在这里被撤销：**结果的两列**（`outcome` 与 `isError`）合成一列，因为
  对一次工具调用它们把同一件事说了两遍，而对非工具行只有其中一列有值；以及**哈希链**——它是「这条轨**完整**」
  的**证据**、不是轨本身——挪到行下面、并折起来。

  链仍在本页校验，它的结论仍显示在行的表头，因为**一条断了的链**是这里唯一凌驾于其余一切之上的东西。
-->
<template>
  <ContentWrap :title="t('keelbase.audit.rowsTitle')">
    <template #header>
      <!-- The chain's verdict leads, because a break outranks anything the rows can say. -->
      <!-- 链的结论打头，因为一次断裂压倒这些行能说的一切。 -->
      <el-tag v-if="verify" :type="verify.valid ? 'success' : 'danger'" size="small">
        {{ verify.valid ? t('keelbase.audit.chainValid') : t('keelbase.audit.chainBroken', { at: verify.brokenIndex ?? '-' }) }}
      </el-tag>
      <el-button class="ml-auto" size="small" :loading="rowsLoading" @click="reload">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <el-form :inline="true" :model="query" class="-mb-15px" @submit.prevent>
      <el-form-item :label="t('keelbase.audit.userId')">
        <el-input v-model="query.userId" clearable :placeholder="t('keelbase.audit.userIdPlaceholder')" class="!w-200px" @keyup.enter="reload" />
      </el-form-item>
      <el-form-item :label="t('keelbase.audit.since')">
        <el-date-picker
          v-model="since"
          type="datetime"
          :placeholder="t('keelbase.audit.sincePlaceholder')"
          class="!w-220px"
        />
      </el-form-item>
      <el-form-item :label="t('keelbase.audit.isError')">
        <el-select v-model="query.isError" clearable class="!w-140px">
          <el-option :label="t('keelbase.audit.statusError')" value="true" />
          <el-option :label="t('keelbase.audit.statusOk')" value="false" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">{{ t('keelbase.common.filter') }}</el-button>
      </el-form-item>
    </el-form>

    <div v-if="rowsError" class="text-[var(--el-color-danger)] mb-10px">{{ rowsError }}</div>
    <el-table v-loading="rowsLoading" :data="rows" stripe>
      <!-- Opening a row lays out the whole of it in one place: the sentence the list compresses. -->
      <!-- 摊开一行，就是把列表压住的那句话一次摆全。 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="row-detail">
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWho') }}</span>
              <span>{{ callerName(row.userId) }} <span class="muted">#{{ row.userId ?? '—' }}</span></span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colTime') }}</span>
              <span>{{ timeFormatter(row) }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWhat') }}</span>
              <span>{{ actionText(row.action) }}<template v-if="row.tool"> · {{ row.tool }}</template></span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colResult') }}</span>
              <span>
                <el-tag :type="outcomeTagType(row.outcome, row.isError)" size="small">{{ resultText(row) }}</el-tag>
                <!-- `attempted` is not a verdict; say so where the row is read in full. -->
                <!-- `attempted` 不是一个判定；在整行被读的地方把这一点说出来。 -->
                <span v-if="row.outcome === 'attempted'" class="muted ml-8px">{{ t('keelbase.audit.attemptedHint') }}</span>
              </span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colDetail') }}</span>
              <span>{{ row.detail || '—' }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colId') }}</span>
              <span>#{{ row.id }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colTime')" width="175">
        <template #default="{ row }">{{ timeFormatter(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colWho')" width="170">
        <template #default="{ row }">
          <div>{{ callerName(row.userId) }}</div>
          <div class="muted">#{{ row.userId ?? '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colWhat')" min-width="230">
        <template #default="{ row }">
          <div>{{ actionText(row.action) }}</div>
          <div v-if="row.tool" class="muted">{{ row.tool }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colResult')" width="150">
        <template #default="{ row }">
          <el-tag :type="outcomeTagType(row.outcome, row.isError)" size="small">{{ resultText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colDetail')" prop="detail" show-overflow-tooltip min-width="240" />
    </el-table>

    <!--
      `/audit/logs` answers a bare list: it takes a window and returns what is in it, with no total
      to page against. So this is a walk forward and back over that window, and it says where it is
      rather than pretending to know how much is left.

      `/audit/logs` 回的是一个裸列表：它收一个窗口、把窗口里的东西给回来，**没有总数**可供分页。故
      这里是沿那个窗口前后走一步，并且**如实说出自己在哪**，而不是假装知道还剩多少。
    -->
    <div class="pager">
      <el-button size="small" :disabled="offset === 0 || rowsLoading" @click="step(-1)">
        {{ t('keelbase.audit.prev') }}
      </el-button>
      <span class="pager-pos">{{ t('keelbase.audit.window', { from: rows.length ? offset + 1 : 0, n: rows.length }) }}</span>
      <el-button size="small" :disabled="!hasMore || rowsLoading" @click="step(1)">
        {{ t('keelbase.audit.next') }}
      </el-button>
    </div>
  </ContentWrap>

  <!--
    The walk over the chain — evidence, folded shut by default so it does not read as the headline.
    It is drawn below the rows on purpose: the rows are what an auditor came for, and the chain is
    what lets them trust them.

    走一遍链——**证据**，默认折起来，免得被读成标题。它刻意画在行**下面**：行是审计人员来读的东西，
    而链是让他们**信**那些行的东西。
  -->
  <ContentWrap :title="t('keelbase.audit.chainTitle')">
    <template #header>
      <span v-if="verify" class="text-13px text-[var(--el-text-color-secondary)]">
        {{ t('keelbase.audit.chainChecked', { n: verify.checked }) }}
      </span>
      <el-button class="ml-auto" size="small" :loading="verifyLoading" @click="loadVerify">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <div v-if="verifyError" class="text-[var(--el-color-danger)]">{{ verifyError }}</div>
    <div v-else-if="!verify" class="text-[var(--el-text-color-secondary)]">{{ t('keelbase.common.loading') }}</div>
    <el-empty v-else-if="verify.chain.length === 0" :description="t('keelbase.audit.chainEmpty')" />
    <el-collapse v-else>
      <el-collapse-item name="chain">
        <template #title>
          <span class="text-13px">
            {{ verify.valid ? t('keelbase.audit.chainValid') : t('keelbase.audit.chainBroken', { at: verify.brokenIndex ?? '-' }) }}
          </span>
        </template>
        <div class="chain">
          <div v-for="n in verify.chain" :key="n.id" class="chain-node" :class="{ broken: n.broken }">
            <span class="chain-id">#{{ n.id }}</span>
            <span class="chain-time">{{ chainTime(n.createdAt) }}</span>
            <span class="chain-action">{{ n.action || '—' }}</span>
            <code class="chain-hash">{{ short(n.prevHash) }} → {{ short(n.hash) }}</code>
            <el-tag v-if="n.broken" type="danger" size="small">{{ t('keelbase.audit.broken') }}</el-tag>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import { auditApi, type AuditRow, type ChainVerification } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { actionLabelKey, outcomeLabelKey, outcomeTagType } from '@/keelbase/labels'
import { resolveCallerNames } from '@/keelbase/callerNames'

defineOptions({ name: 'KeelbaseGovernanceAuditChain' })

const { t } = useI18n()

const LIMIT = 20

const verify = ref<ChainVerification | null>(null)
const verifyLoading = ref(false)
const verifyError = ref('')

const rows = ref<AuditRow[]>([])
const rowsLoading = ref(false)
const rowsError = ref('')
const offset = ref(0)
const hasMore = ref(false)
const since = ref<Date | null>(null)

/** Caller names for the ids on screen, filled in after the rows land. */
/** 屏幕上那些 id 的调用方姓名，在行落地之后补上。 */
const names = ref<Record<string, string>>({})

const query = reactive<{ userId: string; isError: string }>({ userId: '', isError: '' })

function callerName(id: string | null | undefined): string {
  if (!id) return '—'
  return names.value[String(id)] ?? String(id)
}

function actionText(action: string | null | undefined): string {
  const key = actionLabelKey(action)
  return key ? t(key) : (action || '—')
}

/** One word for what came of the row: the outcome's word, or the plain status when there is none. */
/** 给这一行的**结局**一个词：outcome 的词；没有时就给朴素的状态词。 */
function resultText(row: AuditRow): string {
  const key = outcomeLabelKey(row.outcome)
  if (key) return t(key)
  return row.isError ? t('keelbase.audit.statusError') : t('keelbase.audit.statusOk')
}

function timeFormatter(row: AuditRow): string {
  return row.createdAt ? row.createdAt.replace('T', ' ').slice(0, 19) : '—'
}

function chainTime(v: string | null | undefined): string {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

function short(v: string | null | undefined): string {
  return (v || '—').slice(0, 10)
}

async function loadVerify() {
  verifyLoading.value = true
  verifyError.value = ''
  try {
    verify.value = await auditApi.verify()
  } catch (e) {
    verify.value = null
    verifyError.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    verifyLoading.value = false
  }
}

async function loadRows() {
  rowsLoading.value = true
  rowsError.value = ''
  try {
    const data = await auditApi.logs({
      userId: query.userId || undefined,
      isError: query.isError || undefined,
      since: since.value ? since.value.toISOString() : undefined,
      limit: LIMIT,
      offset: offset.value,
    })
    rows.value = data
    // A full window means there may be more; a short one means this is the end of it.
    // 窗口满了就**可能**还有；不满就说明这里是它的尽头。
    hasMore.value = data.length === LIMIT
    // Names are an enrichment: the rows are already rendered, and an id stands in until the host's
    // directory answers. The lookup never rejects, so a failure here leaves the ids in place.
    // 姓名是**补上去的**：行已经渲染，在宿主的名录答复之前由 id 顶着。那次查询从不 reject，故它失败时
    // 留下的就是那些 id。
    const resolved = await resolveCallerNames(data.map((r) => r.userId))
    names.value = { ...names.value, ...resolved }
  } catch (e) {
    rows.value = []
    hasMore.value = false
    rowsError.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    rowsLoading.value = false
  }
}

function reload() {
  offset.value = 0
  void loadRows()
}

function step(delta: number) {
  offset.value = Math.max(0, offset.value + delta * LIMIT)
  void loadRows()
}

onMounted(() => {
  void loadVerify()
  void loadRows()
})
</script>

<style scoped>
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
.chain {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 280px;
  overflow-y: auto;
}
.chain-node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
}
.chain-node.broken {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.chain-id {
  font-weight: 600;
  min-width: 44px;
}
.chain-time {
  color: var(--el-text-color-secondary);
  min-width: 150px;
}
.chain-action {
  flex: 1;
}
.chain-hash {
  font-family: monospace;
  color: var(--el-text-color-secondary);
}
.pager {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.pager-pos {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
