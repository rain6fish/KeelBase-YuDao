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
      <el-table-column :label="t('keelbase.effects.colId')" prop="id" width="90" align="center" />
      <el-table-column :label="t('keelbase.effects.colTool')" prop="toolName" width="200" />
      <el-table-column :label="t('keelbase.effects.colTarget')" width="240">
        <template #default="{ row }">{{ row.targetTitle || `#${row.resultId}` }}</template>
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

defineOptions({ name: 'KeelbaseGovernanceTools' })

const { t } = useI18n()

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
