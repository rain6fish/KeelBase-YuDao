<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What this deployment's tools are, and what the gate will do with each.

  The reference's page of the same name also carries a content-safety policy above this table. That
  is not drawn here: the settings it reads belong to the reference's own store, and this runtime has
  no such surface. What is drawn is the frozen `ai-tool-inventory` object, read off the endpoint the
  platform line serves.

  Two of its fields are empty in this runtime — `allowedRoles` and `permissions` — because the gate
  decides on risk level alone. They are shown as empty rather than hidden, and the note under the
  table says why, so an empty column reads as a fact about this deployment instead of a page that
  failed to load something.

  本部署的工具是什么，以及门会对它们各自做什么。

  参照实现那个同名页面在这张表上方还有内容安全策略。这里不画：它读的设置属于参照实现自己的存储，而
  本运行时没有这个面。画出的是**冻结对象** `ai-tool-inventory`，取自**平台线**供的那条端点。

  它有两个字段在本运行时是**空的**——`allowedRoles` 与 `permissions`——因为门**只看风险级**。它们**如实
  显示为空**、而不是藏起来，表下那句话说明原因，好让一个空列读起来是**本部署的一条事实**，而不是
  「有个东西没加载出来」。
-->
<template>
  <ContentWrap :title="t('keelbase.toolInv.title')">
    <template #header>
      <el-tag v-if="items.length" size="small" effect="plain">{{ t('keelbase.toolInv.count', { n: items.length }) }}</el-tag>
      <el-button class="ml-auto" size="small" :loading="loading" @click="load">{{ t('keelbase.common.refresh') }}</el-button>
    </template>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column :label="t('keelbase.toolInv.colTool')" prop="name" width="220" />
      <el-table-column :label="t('keelbase.toolInv.colRisk')" width="150">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.riskLevel }}</el-tag>
          <span class="ml-6px text-12px text-[var(--el-text-color-secondary)]">{{ row.riskStrategy }}</span>
        </template>
      </el-table-column>
      <!-- The gate the call will actually meet — the server reads it off the decision, not off the
           declaration, and the page does not second-guess it. The word is the plain one (`auto`,
           `confirm`, `approval`, `blocked` in the reader's language), not the code. -->
      <!-- 这次调用**真会**遇到的那道闸 —— 服务端是从**判定**读出来的、不是从声明，而页面不替它重算。词用的是
           朴素的那些（读者语言里的 `auto`/`confirm`/`approval`/`blocked`），不是码。 -->
      <el-table-column :label="t('keelbase.toolInv.colGate')" width="140">
        <template #default="{ row }">
          <el-tag :type="gateTagType(row.gateMode)" size="small">{{ gateText(row.gateMode) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colRevoke')" width="150">
        <template #default="{ row }">{{ row.revokeClass || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colParams')" min-width="200">
        <template #default="{ row }">
          <span v-if="row.parameters.length" class="text-12px">
            {{ row.parameters.map((p: ToolParameterRow) => `${p.name}${p.required ? '*' : ''}`).join(', ') }}
          </span>
          <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colRoles')" width="180">
        <template #default="{ row }">
          <span v-if="row.allowedRoles.length" class="text-12px">{{ row.allowedRoles.join(', ') }}</span>
          <span v-else class="text-[var(--el-text-color-secondary)]">{{ t('keelbase.toolInv.none') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colDescription')" min-width="320" prop="description" show-overflow-tooltip />
    </el-table>

    <!-- The two empty fields are a fact about the runtime, not a loading failure; said here so the
         empty columns cannot be read as the latter. -->
    <!-- 那两个空字段是**运行时**的一条事实、不是加载失败；写在这里，好让空列不会被读成后者。 -->
    <div v-if="items.length" class="mt-10px text-12px text-[var(--el-text-color-secondary)]">
      {{ t('keelbase.toolInv.emptyNote') }}
    </div>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'
import { toolApi, type ToolInventoryItem, type ToolParameterRow } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { gateLabelKey, gateTagType } from '@/keelbase/labels'

defineOptions({ name: 'KeelbaseGovernanceToolInventory' })

const { t } = useI18n()

const items = ref<ToolInventoryItem[]>([])
const loading = ref(false)
const error = ref('')

/** The gate in the reader's words; an unknown code is shown as itself. */
/** 门槛的**人话**；不认识的码原样显示。 */
function gateText(gate: string): string {
  const key = gateLabelKey(gate)
  return key ? t(key) : gate
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await toolApi.inventory()
  } catch (e) {
    items.value = []
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>
