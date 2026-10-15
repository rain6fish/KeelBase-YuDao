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
    <!-- A description of this page, said as such — the window is all the face gives. -->
    <!-- **本页**的描述，并如实这么说 —— 这条面给的就是一个窗口。 -->
    <div v-if="rows.length && !rowsError" class="overview">{{ overview }}</div>
    <el-table v-loading="rowsLoading" :data="rows" stripe @expand-change="(row: AuditRow) => void onExpand(row)">
      <!-- Opening a row lays out the whole of it in one place: the sentence the list compresses. -->
      <!-- 摊开一行，就是把列表压住的那句话一次摆全。 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="row-detail">
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWho') }}</span>
              <span>{{ callerName(row.userId) }} <span v-if="row.userId" class="muted">#{{ row.userId }}</span></span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colTime') }}</span>
              <span>{{ timeFormatter(row) }}</span>
            </div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.audit.colWhat') }}</span>
              <span>
                {{ whatText(row) }}
                <span class="muted">{{ actionText(row.action) }}<template v-if="row.tool"> · {{ row.tool }}</template></span>
              </span>
            </div>
            <!-- What the tool says it does, in its own words — its declaration, not our summary of it. -->
            <!-- 工具**自己**说它做什么 —— 是它的声明，不是我们对它的概括。 -->
            <div v-if="toolDescription(row)" class="d-row">
              <span class="d-key">{{ t('keelbase.audit.toolDeclared') }}</span>
              <span>
                {{ toolDescription(row) }}
                <el-tag v-if="toolRisk(row)" size="small" effect="plain" class="ml-6px">{{ toolRisk(row) }}</el-tag>
                <span v-if="toolGateOf(row)" class="muted ml-6px">{{ toolGateOf(row) }}</span>
              </span>
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
            <!-- What that means, in one line: the part a single word cannot carry. -->
            <!-- 那**意味着**什么，一句话：一个词载不动的那部分。 -->
            <div v-if="meaningText(row)" class="d-row">
              <span class="d-key">{{ t('keelbase.audit.meaning') }}</span>
              <span>{{ meaningText(row) }}</span>
            </div>
            <!-- The record this write landed on — only when exactly one candidate matches, and the
                 matching rule is named here rather than left for a reader to assume. -->
            <!-- 这次写落在的那条记录 —— 只在**恰好一个**候选对得上时显示，而配对规则写在**这里**，不留
                 给读者去假设。 -->
            <div v-if="recordText(row)" class="d-row">
              <span class="d-key">{{ t('keelbase.audit.writeRecord') }}</span>
              <span>{{ recordText(row) }}</span>
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
          <!-- 没有 id 的行**不画那一行**：`#—` 读起来像一个坏掉的 id，而不是「这次没人可挂」。 -->
          <div v-if="row.userId" class="muted">#{{ row.userId }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colWhat')" min-width="240">
        <template #default="{ row }">
          <div>{{ whatText(row) }}</div>
          <div class="muted">{{ actionText(row.action) }}<template v-if="row.tool"> · {{ row.tool }}</template></div>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import {
  auditApi,
  effectApi,
  toolApi,
  type AuditRow,
  type ChainVerification,
  type EffectRow,
  type ToolInventoryItem,
} from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import {
  actionLabelKey,
  gateLabelKey,
  outcomeLabelKey,
  outcomeMeaningKey,
  outcomeTagType,
  toolLabelKey,
} from '@/keelbase/labels'
import { resolveCallerNames } from '@/keelbase/callerNames'
import { maskTitle } from '@/keelbase/mask'
import { resolveRecordName } from '@/keelbase/recordNames'

defineOptions({ name: 'KeelbaseGovernanceAuditChain' })

const { t } = useI18n()

const LIMIT = 20

/** The order a summary reads outcomes in: what happened first, what is waiting last. */
/** 概览里结局的次序：先「已经发生的」，把「在等的」放在后面。 */
const OVERVIEW_ORDER = [
  'executed', 'approved', 'pending', 'attempted', 'blocked', 'refused', 'declined', 'failed', 'timeout', 'revoked',
]

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

/**
 * What each tool is, by the name it registers — read once from the inventory the runtime serves. A
 * business reader needs this: the row's action says 「工具调用」 and its tool says `create_crm_customer`,
 * and neither tells them it created a customer.
 *
 * 每个工具是什么，按它注册的名字 —— 从运行时那条清单读一次。业务读者**需要**它：行为那列写着「工具调用」、
 * 工具那列写着 `create_crm_customer`，两者都没告诉他：它新建了一个客户。
 */
const inventory = ref<Record<string, ToolInventoryItem>>({})

/**
 * The ledger of what the AI wrote, fetched beside the audit rows. It is what makes 「点开能看到新建了哪个
 * 客户」 answerable at all: the audit row names the tool, and only the ledger knows the record.
 *
 * 副作用台账 —— 与审计行一起取回来。它正是「点开能看到新建了哪个客户」答得上来的原因：审计行只说出工具，
 * **只有台账知道那条记录**。
 */
const effects = ref<EffectRow[]>([])

/**
 * What each matched record is called, asked of this host on the row that needs it. Resolved when a row
 * is opened rather than for the whole page: one request per row a reader actually looks at, and the
 * host is asked only about records the ledger already named.
 *
 * 对上的每条记录叫什么 —— 在**需要它的那一行**上问宿主。**摊开时**才解析、不是整页都解析：读者真去看的
 * 那一行才发一次请求，而宿主只会被问到台账**已经点过名**的那些记录。
 */
const recordNames = ref<Record<string, string>>({})

/** Ask the host about the row's record once, when the row is opened. */
/** 行被摊开时，问宿主一次那条记录。 */
async function onExpand(row: AuditRow) {
  const e = recordOf(row)
  if (!e || e.resultId === null || e.resultId === undefined) return
  const key = `${e.resultType ?? ''}#${e.resultId}`
  if (recordNames.value[key] !== undefined) return
  const name = await resolveRecordName(e.resultType, e.resultId)
  recordNames.value = { ...recordNames.value, [key]: name }
}

/** How many ledger rows to look through when matching. Bounded on purpose, and said on the page. */
/** 配对时翻多少条台账行。**刻意有界**，并在页面上说出来。 */
const EFFECT_WINDOW = 50

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

/** The row's outcome as a vocabulary word, falling back to the plain status for a non-tool row. */
/** 这一行的**结局**，按词表取值；非工具行回落到朴素的状态词。 */
function effectiveOutcome(row: AuditRow): string {
  if (outcomeLabelKey(row.outcome)) return row.outcome as string
  return row.isError ? 'failed' : 'executed'
}

/** The tool in the reader's language, falling back to the registered name. */
/** 工具名，用读者的语言；不认识就保留注册名。 */
function toolText(name: string | null | undefined): string {
  if (!name) return '—'
  const key = toolLabelKey(name)
  return key ? t(key) : name
}

/** What this row did, in the reader's language: the tool if there is one, else the action. */
/** 这一行**做了什么**，用读者的语言：有工具就用工具，没有就用动作。 */
function whatText(row: AuditRow): string {
  return row.tool ? toolText(row.tool) : actionText(row.action)
}

/** The tool's own declaration — what it says it does — when the inventory knows this tool. */
/** 工具**自己声明的**用途 —— 清单认识这个工具时就用它。 */
function toolDescription(row: AuditRow): string {
  return (row.tool && inventory.value[row.tool]?.description) || ''
}

/** What this tool's gate does to it — the effective one, in the reader's language. */
/** 这个工具的闸会把它怎么样 —— 生效的那道，用读者的语言。 */
function toolGateOf(row: AuditRow): string {
  const mode = row.tool ? inventory.value[row.tool]?.gateMode : ''
  const key = gateLabelKey(mode)
  return key ? t(key) : (mode || '')
}

/**
 * The write record this row landed on, when **exactly one** candidate matches. The two trails carry no
 * shared key — the audit says what a call came to, the ledger says what it wrote — so the only honest
 * match is tool plus a close timestamp, and only when that leaves one candidate. A guess here would put
 * one record beside another person's action, which is worse than showing nothing at all.
 *
 * 这一行落在哪条写入记录上 —— **恰好只有一个**候选项时。两条轨之间没有共同的键（审计说「一次调用怎么了」、
 * 台账说「它写了什么」），故唯一诚实的对法是**工具 ＋ 相近的时刻**，且只在它**只剩一个**候选时成立。
 * 在这里猜，会把**一条记录摆到另一个人的动作旁边** —— 那比什么都不显示更糟。
 */
function recordOf(row: AuditRow): EffectRow | null {
  if (!row.tool) return null
  const at = Date.parse(row.createdAt)
  const near = effects.value.filter(
    (e) => e.toolName === row.tool && Math.abs(Date.parse(e.createdAt) - at) <= 5000,
  )
  return near.length === 1 ? near[0] : null
}

/** Whether that record is still there — read off the ledger row, never re-derived. */
/** 那条记录**还在不在** —— 按台账行读，绝不自己重算。 */
function recordState(e: EffectRow): string {
  if (!e.targetExists) return t('keelbase.effects.stateGone')
  if (e.targetSoftDeleted) return t('keelbase.effects.stateDeleted')
  return t('keelbase.effects.statePresent')
}

/** The matched record as one line: what it is, which one, and its masked title. */
/** 对上的那条记录，一行说完：是什么、哪一条、以及**掩码后的**标题。 */
function recordText(row: AuditRow): string {
  const e = recordOf(row)
  if (!e) return ''
  // The title is drawn only where the runtime answered one: it names this deployment's sample module's
  // records and answers nothing for the others, and a "masked" note beside a title that never existed
  // would describe an act nobody performed.
  // 标题只在运行时**真答了**的时候画：它只给本部署**样例模块**的记录配标题、对其余的一律不答；而在一个
  // **本来就没有**的标题旁边写「已掩码」，是在描述一件没人做过的事。
  const parts = [e.resultType || '—', `#${e.resultId ?? '—'}`]
  // The host's own answer first (asked when the row was opened), then what the ledger carried. Either
  // way it is masked before it is drawn.
  // 先用**宿主自己的答复**（行被摊开时问的），再用台账带的那一份。无论哪一份，**画出来之前都先掩码**。
  const shown = recordNames.value[`${e.resultType ?? ''}#${e.resultId}`] || e.targetTitle
  if (shown) parts.push(maskTitle(shown))
  parts.push(recordState(e))
  return `${parts.join(' · ')} ${t('keelbase.audit.matchNote')}`
}

/** What the outcome means, in one line — the part a single word cannot carry. */
/** 这个结局**意味着什么**，一句话 —— 一个词载不动的那部分。 */
function meaningText(row: AuditRow): string {
  const key = outcomeMeaningKey(effectiveOutcome(row))
  return key ? t(key) : ''
}

/**
 * A summary of **this page**, said as such. The window is all this face gives — there is no total
 * behind it to divide by — so it is drawn as a description of what is on screen rather than as a
 * statistic, which would be a claim the runtime cannot back.
 *
 * **本页**的概览，并**如实这么说**。这条面给的就是一个窗口 —— 它背后没有总数可供去除 —— 故这一段画的是
 * **屏幕上这些行**的描述，而不是一个统计量：后者是运行时**撑不住**的主张。
 */
const overview = computed(() => {
  const counts = new Map<string, number>()
  for (const row of rows.value) {
    const key = effectiveOutcome(row)
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const parts = OVERVIEW_ORDER.filter((o) => counts.has(o)).map(
    (o) => `${t(`keelbase.vocab.outcome.${o}`)} ${counts.get(o)}`,
  )
  const tools = new Set(rows.value.map((r) => r.tool).filter(Boolean)).size
  const callers = new Set(rows.value.map((r) => r.userId).filter(Boolean)).size
  const head = t('keelbase.audit.overviewRows', { n: rows.value.length })
  const scope = t('keelbase.audit.overviewScope', { tools, callers })
  return `${head}${parts.length ? '：' + parts.join(' · ') : ''} ｜ ${scope}`
})

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
    // The inventory is read once and kept: it answers the same for every window, and it is what turns a
    // tool's registered name into something a business reader can act on.
    // 清单只读一次并留着：它对每个窗口都一样，而它正是把工具的注册名变成业务读者看得懂的东西的那一步。
    if (!Object.keys(inventory.value).length) {
      const tools = await toolApi.inventory()
      inventory.value = Object.fromEntries(tools.map((toolItem) => [toolItem.name, toolItem]))
    }
    // The ledger, for the drill-down only: it is the one face that knows which record a write landed
    // on. Bounded to one window of it, and the page says so where it uses it.
    // 台账，只给下钻用：它是唯一知道「这次写落在哪条记录上」的那条面。只取一窗，且页面在用到它的地方
    // 把这一点说出来。
    const ledger = await effectApi.list(1, EFFECT_WINDOW)
    effects.value = ledger.items
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
.overview {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-regular);
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
