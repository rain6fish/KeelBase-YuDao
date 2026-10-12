<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The audit chain, as this host's runtime can answer it.

  Two panels, and no third. The reference console draws statistics cards, a by-day trend, an
  interpretation block, an identity chain and a conversation drill-down beside these two — all of
  them fed by routes this runtime does not have. Drawing them here would mean drawing empty boxes,
  so what survives is what the runtime actually answers: the walk over the chain, and the rows it
  walked.

  The filters are the three the runtime honours. It *refuses* `agentId` and `orgId` rather than
  ignoring them, on the stated ground that a filter accepted and not applied returns a list that
  looks filtered and is not — so the page does not offer them either.

  审计链，按本宿主的运行时能答的样子。

  两块，没有第三块。参照实现会在这两块旁边再画统计卡、按日趋势、解释块、身份链与会话下钻——它们
  全都由一个本运行时没有的路由喂。在这里画它们等于画空盒子，故留下的是运行时真答得出来的：走一遍链，
  以及它走过的那些行。

  筛选是运行时**认**的那三个。`agentId` 与 `orgId` 是被**拒绝**的、不是被忽略的，理由写在代码里：
  一个被接受却没被施加的筛选会回出一份看着像筛过、其实没有的列表——故页面也不提供它们。
-->
<template>
  <ContentWrap :title="t('keelbase.audit.chainTitle')">
    <template #header>
      <el-tag v-if="verify" :type="verify.valid ? 'success' : 'danger'" size="small">
        {{ verify.valid ? t('keelbase.audit.chainValid') : t('keelbase.audit.chainBroken', { at: verify.brokenIndex ?? '-' }) }}
      </el-tag>
      <span v-if="verify" class="ml-10px text-13px text-[var(--el-text-color-secondary)]">
        {{ t('keelbase.audit.chainChecked', { n: verify.checked }) }}
      </span>
      <el-button class="ml-auto" size="small" :loading="verifyLoading" @click="loadVerify">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <div v-if="verifyError" class="text-[var(--el-color-danger)]">{{ verifyError }}</div>
    <div v-else-if="!verify" class="text-[var(--el-text-color-secondary)]">{{ t('keelbase.common.loading') }}</div>
    <el-empty v-else-if="verify.chain.length === 0" :description="t('keelbase.audit.chainEmpty')" />
    <div v-else class="chain">
      <div v-for="n in verify.chain" :key="n.id" class="chain-node" :class="{ broken: n.broken }">
        <span class="chain-id">#{{ n.id }}</span>
        <span class="chain-action">{{ n.action || '—' }}</span>
        <code class="chain-hash">{{ short(n.prevHash) }} → {{ short(n.hash) }}</code>
        <el-tag v-if="n.broken" type="danger" size="small">{{ t('keelbase.audit.broken') }}</el-tag>
      </div>
    </div>
  </ContentWrap>

  <ContentWrap :title="t('keelbase.audit.rowsTitle')">
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
      <el-table-column :label="t('keelbase.audit.colId')" prop="id" width="90" align="center" />
      <el-table-column :label="t('keelbase.audit.colTime')" prop="createdAt" width="200" :formatter="timeFormatter" />
      <el-table-column :label="t('keelbase.audit.colUser')" prop="userId" width="140" />
      <el-table-column :label="t('keelbase.audit.colAction')" prop="action" width="140" />
      <el-table-column :label="t('keelbase.audit.colTool')" prop="tool" width="180" />
      <el-table-column :label="t('keelbase.audit.colOutcome')" prop="outcome" width="130" />
      <el-table-column :label="t('keelbase.audit.colStatus')" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isError ? 'danger' : 'success'" size="small">
            {{ row.isError ? t('keelbase.audit.statusError') : t('keelbase.audit.statusOk') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.audit.colDetail')" prop="detail" show-overflow-tooltip />
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
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import { auditApi, type AuditRow, type ChainVerification } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'

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

const query = reactive<{ userId: string; isError: string }>({ userId: '', isError: '' })

function short(v: string | null | undefined): string {
  return (v || '—').slice(0, 10)
}

function timeFormatter(row: AuditRow): string {
  return row.createdAt ? row.createdAt.replace('T', ' ').slice(0, 19) : '—'
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
