<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The adversarial demonstration, run against this deployment.

  Four scenarios, not the reference's five, and the page says so in its own body rather than in a
  document nobody opens: two of the reference's cases have nothing to land on here (there is no
  tool at a blocking risk level in this deployment, and no prompt for an injection to reach). What is
  shown is the runtime's own answer, with the engine's raw status beside it, so a reader can see both
  the verdict and the word the engine used for it.

  Each run is on demand. Nothing here runs by itself: a demonstration that fires when a page loads is
  a demonstration nobody chose to watch, and the confirmation scenario leaves a token behind.

  对抗性演示，跑**本部署自己**。

  四条场景，不是参照实现那五条——而这一点写在**页面正文里**，不写在一份没人会打开的文档里：参照实现
  那五条里有两条在这里**没有可落的东西**（本部署里没有一个处在阻断档的工具，也没有提示词可供注入）。
  显示的是**运行时自己的答复**，并把**引擎的原始状态**放在旁边，故读者看到的既是判定、也是引擎对它的叫法。

  每一次运行都**按需**发起。这里没有任何东西自己跑：一个页面加载就开火的演示，是一场**没人选择去看**的
  演示——而且确认那条会留下一个 token。
-->
<template>
  <ContentWrap :title="t('keelbase.showcase.title')">
    <template #header>
      <el-button size="small" :loading="loading" @click="load">{{ t('keelbase.common.refresh') }}</el-button>
    </template>

    <!-- The caveat belongs on the page, not only in a file: a reader who only ever sees this screen
         should know what it is not. -->
    <!-- 这条保留句属于**页面本身**，不能只写在文件里：一个只见过这个屏幕的读者，也该知道它不是什么。 -->
    <el-alert type="info" :closable="false" class="mb-10px" :title="t('keelbase.showcase.caveat')" />

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column :label="t('keelbase.showcase.colId')" prop="id" width="150" />
      <el-table-column :label="t('keelbase.showcase.colDrives')" prop="drives" min-width="280" />
      <el-table-column :label="t('keelbase.showcase.colExpect')" prop="expect" min-width="220" />
      <el-table-column :label="t('keelbase.showcase.colOutcome')" width="170">
        <template #default="{ row }">
          <template v-if="row.result">
            <el-tag :type="outcomeTag(row.result.outcome)" size="small">{{ row.result.outcome }}</el-tag>
            <span v-if="row.result.engine" class="ml-6px text-12px text-[var(--el-text-color-secondary)]">
              {{ row.result.engine }}
            </span>
          </template>
          <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.showcase.colDetail')" min-width="300">
        <template #default="{ row }">
          <span v-if="row.result" class="text-12px">{{ row.result.detail }}</span>
          <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.showcase.colRun')" width="110" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" :loading="running === row.id" :disabled="running !== null" @click="run(row)">
            {{ t('keelbase.showcase.run') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ContentWrap } from '@/components/ContentWrap'
import { showcaseApi, type ShowcaseResult, type ShowcaseScenario } from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'

defineOptions({ name: 'KeelbaseGovernanceShowcase' })

const { t } = useI18n()

type Row = ShowcaseScenario & { result?: ShowcaseResult }

const rows = ref<Row[]>([])
const loading = ref(false)
const error = ref('')
const running = ref<string | null>(null)

/** A refused call and a call that did not run read differently, so the tag says which happened. */
/** 「被拒绝」与「没跑成」读起来不一样，故标签说出发生的是哪一种。 */
function outcomeTag(outcome: string): 'success' | 'danger' | 'warning' | 'info' {
  if (outcome === 'allowed' || outcome === 'denied' || outcome === 'unresolved') return 'success'
  if (outcome === 'requiresConfirmation') return 'warning'
  if (outcome === 'unknown-scenario') return 'info'
  // Anything the scenarios call a finding: the call did not do what the scenario exists to show.
  // 一切被场景称作「发现」的结果：这次调用没有做到那条场景存在来展示的事。
  return 'danger'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await showcaseApi.scenarios()).map((s) => ({ ...s }))
  } catch (e) {
    rows.value = []
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function run(row: Row) {
  running.value = row.id
  try {
    row.result = await showcaseApi.run(row.id)
  } catch (e) {
    ElMessage.error(runtimeErrorMessage(e, t('keelbase.showcase.runFailed')))
  } finally {
    running.value = null
  }
}

onMounted(() => {
  void load()
})
</script>
