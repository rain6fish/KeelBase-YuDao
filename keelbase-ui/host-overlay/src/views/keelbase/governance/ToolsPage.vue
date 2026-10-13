<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The ledger of what AI has written, and the one action that can be taken on it.

  The reference console also shows a tool inventory and a content-safety policy above this table.
  Neither is drawn: this runtime serves no `/ai/tools`, and its content-safety settings belong to the
  reference's own settings store. Rows whose effects can be claimed or whose disputes can be
  acknowledged are likewise not surfaced — those two routes do not exist here. So the page is the
  ledger and the revoke, which is what this host can back.

  The user filter is the manager's; the server scopes every caller regardless of what it asks for, so
  a non-manager sees their own rows here too and the page does not pretend otherwise.

  AI 写了什么的台账，以及能在它上面做的唯一一件事。

  参照实现还在这张表上方显示工具清单与内容安全策略。两者都不画：本运行时没有 `/ai/tools` 这条路由，
  而它的内容安全设置属于参照实现自己的配置存储。可认领的、可确认争议的那些行同样不露出——那两条路由
  在这里不存在。故这一页就是台账与撤销，也就是本宿主撑得住的东西。

  用户筛选是管理员用的；**服务端**对每个调用方都施加了范围，无论它请求什么，故非管理员在这里看到的
  也是自己那些行，页面不假装不是。
-->
<template>
  <ContentWrap :title="t('keelbase.tools.ledgerTitle')">
    <template #header>
      <el-tag v-if="total" size="small" effect="plain">{{ t('keelbase.actionCenter.writesTotal', { n: total }) }}</el-tag>
      <el-button class="ml-auto" size="small" :loading="loading" @click="refresh">
        {{ t('keelbase.common.refresh') }}
      </el-button>
    </template>

    <el-form :inline="true" class="-mb-15px" @submit.prevent>
      <el-form-item :label="t('keelbase.tools.userId')">
        <el-input v-model="userId" clearable :placeholder="t('keelbase.tools.userIdPlaceholder')" class="!w-200px" @keyup.enter="refresh" />
        <!-- The filter takes an id; when the host's directory can name that id, say who it is. -->
        <!-- 该筛选收的是 id；当宿主的名录能把这个 id 配上名时，把那**是谁**说出来。 -->
        <span v-if="filteredName" class="ml-8px text-12px text-[var(--el-text-color-secondary)]">{{ filteredName }}</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="refresh">{{ t('keelbase.common.filter') }}</el-button>
      </el-form-item>
    </el-form>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <el-table v-loading="loading" :data="rows" stripe>
      <!-- Opened, this row lays out the record the write landed on. Here the row **is** the effect, so
           the two trails agree without a guess. The record's own text is masked — what a record is
           about is the thing this console must not print (CLAUDE.md §5.5 ②). -->
      <!-- 摊开时，这一行把「这次写落在哪条记录上」摆出来。这里这一行**本身就是**那条副作用，故两条轨
           **无需猜测**就一致。记录自己的文字是**掩码的** —— 一条记录讲的是什么，正是本控制台不得打印的东西
           （`CLAUDE.md` §5.5 ②）。 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="row-detail">
            <div class="d-row"><span class="d-key">{{ t('keelbase.effects.colTool') }}</span><span>{{ toolText(row.toolName) }}</span></div>
            <div class="d-row"><span class="d-key">{{ t('keelbase.effects.colResultType') }}</span><span>{{ row.resultType || '—' }}</span></div>
            <div class="d-row"><span class="d-key">{{ t('keelbase.effects.colTargetId') }}</span><span>#{{ row.resultId ?? '—' }}</span></div>
            <div class="d-row">
              <span class="d-key">{{ t('keelbase.effects.colTarget') }}</span>
              <span>
                {{ maskTitle(row.targetTitle) }}
                <!-- The masking note belongs to a title that exists; see the Action Center's copy. -->
                <!-- 掩码那句话属于**真有过标题**的情形；理由见行动中心那一份。 -->
                <span v-if="row.targetTitle" class="muted">{{ t('keelbase.effects.maskedNote') }}</span>
              </span>
            </div>
            <div class="d-row"><span class="d-key">{{ t('keelbase.effects.colState') }}</span><span>{{ stateText(row) }}</span></div>
            <div class="d-row"><span class="d-key">{{ t('keelbase.effects.colTime') }}</span><span>{{ timeText(row.createdAt) }}</span></div>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colId')" prop="id" width="90" align="center" />
      <el-table-column :label="t('keelbase.effects.colTool')" width="220">
        <template #default="{ row }">{{ toolText(row.toolName) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colTarget')" width="240">
        <template #default="{ row }">
          {{ maskTitle(row.targetTitle) }} <span class="muted">#{{ row.resultId ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colResultType')" prop="resultType" width="150" />
      <el-table-column :label="t('keelbase.effects.colRevokeClass')" prop="revokeClass" width="150" />
      <el-table-column :label="t('keelbase.effects.colStatus')" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'revoked' ? 'info' : 'success'" size="small">
            {{ row.status === 'revoked' ? t('keelbase.effects.statusRevoked') : t('keelbase.effects.statusExecuted') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colTime')" width="190">
        <template #default="{ row }">{{ timeText(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.effects.colActions')" width="110" align="center">
        <template #default="{ row }">
          <el-button v-if="row.revocable" link type="danger" @click="revoke(row)">
            {{ t('keelbase.effects.revoke') }}
          </el-button>
          <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
        </template>
      </el-table-column>
    </el-table>

    <Pagination v-if="total > 0" v-model:page="page" v-model:limit="limit" :total="total" @pagination="load" />
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ContentWrap } from '@/components/ContentWrap'
import Pagination from '@/components/Pagination/index.vue'
import { effectApi, type EffectRow } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { resolveCallerNames } from '@/keelbase/callerNames'
import { toolLabelKey } from '@/keelbase/labels'
import { maskTitle } from '@/keelbase/mask'

defineOptions({ name: 'KeelbaseGovernanceTools' })

const { t } = useI18n()

/** The tool in the reader's language, falling back to the registered name. */
/** 工具名，用读者的语言；不认识就保留注册名。 */
function toolText(name: string | null | undefined): string {
  if (!name) return '—'
  const key = toolLabelKey(name)
  return key ? t(key) : name
}

/** Whether the record this write landed on is still there — read off the row, never re-derived. */
/** 这次写落在的那条记录**还在不在** —— 按行读，绝不自己重算。 */
function stateText(row: EffectRow): string {
  if (!row.targetExists) return t('keelbase.effects.stateGone')
  if (row.targetSoftDeleted) return t('keelbase.effects.stateDeleted')
  return t('keelbase.effects.statePresent')
}

const rows = ref<EffectRow[]>([])
const total = ref(0)
const page = ref(1)
const limit = ref(20)
const loading = ref(false)
const error = ref('')
const userId = ref('')
/** The name behind the id being filtered, when the directory knows one. */
/** 正在筛选的那个 id 背后的名字，当名录认识一个时。 */
const filteredName = ref('')

function timeText(v: string | null | undefined): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await effectApi.list(page.value, limit.value, userId.value || undefined)
    rows.value = res.items
    total.value = res.total
    if (userId.value) {
      const resolved = await resolveCallerNames([userId.value])
      const name = resolved[userId.value]
      filteredName.value = name && name !== userId.value ? name : ''
    } else {
      filteredName.value = ''
    }
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

function refresh() {
  page.value = 1
  void load()
}

function revoke(row: EffectRow) {
  ElMessageBox.confirm(
    t('keelbase.effects.revokeConfirm', { title: row.targetTitle || `#${row.resultId}` }),
    t('keelbase.effects.revoke'),
    { type: 'warning' },
  )
    .then(async () => {
      try {
        await effectApi.revoke(row.id)
        ElMessage.success(t('keelbase.effects.revoked'))
        await load()
      } catch (e) {
        ElMessage.error(runtimeErrorMessage(e, t('keelbase.effects.revokeFailed')))
      }
    })
    .catch(() => undefined)
}

onMounted(() => {
  void load()
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
</style>
