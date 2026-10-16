<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  哪些 AI 路径被治理扣住、哪些不被扣住 —— 一页说清楚。

  **这一页不自己下判断，也不重算任何东西。** 它读的是 UI 构建时由门禁 `check-ungoverned-tool-beans.mjs`
  的 `--emit` 铺进产物的一份记录（`/keelbase-governance-boundary.json`）—— 与 CI 跑的是**同一次计算**。
  记录里只有**标识与枚举**（结论、射程、路径 id、每个 `holds` 项、违规 bean 的坐标），**没有散文**：词由
  这一页按读者的语言给，因为管理台双语是一条红线。
  ⚠ 词条里凡出现**字面 `@`**，必须写成 `{'@'}` —— vue-i18n 把 `@` 当**链接消息**的引导符，未转义的那条消息
  **编译失败**（`SyntaxError`），而 `t()` 是**渲染期**懒编译的 ⇒ 那一下会把**整页渲染**打死，屏幕上只留一个
  空注释、什么也不说。本页的 `reads` 与 `notHeldOneBean` 就踩过这一脚（2026-10-16 实测，见执行日志）。

  底线那半（`stated`）不可能由一次运行**推出** —— 它是设计事实：运行时的缝扣住哪五样；上游那条路今天为什么
  无害（本构建里唯一的 `ToolCallback` bean 是上游自己的示例），以及它**离「不再无害」只差一个 `@Bean`**。
  门禁只能**确认**它。页面上如实画出来，包括那句「只差一个 bean」的告诫。

  Which AI paths this build's governance holds, and which it does not.

  This page decides nothing and recomputes nothing: it reads the record the UI build lays into the artefact
  from the gate's own `--emit` (`/keelbase-governance-boundary.json`) — the same computation CI runs. The
  record carries identifiers and enums only (verdict, scope, path ids, each `holds` entry, an offender's
  coordinates) and no prose, because the console is bilingual by a standing rule and its words come from its
  own i18n.
  ⚠ A literal `@` in a message **has** to be written `{'@'}` — vue-i18n reads `@` as the linked-message sigil,
  the unescaped message fails to compile (`SyntaxError`), and `t()` compiles lazily *at render time*, so that
  one failure takes the whole page's render down and leaves an empty comment saying nothing. This page's
  `reads` and `notHeldOneBean` were bitten by exactly that (measured 2026-10-16; see the execution log).

  The `stated` half cannot be derived by a run — it is a design fact: which five things the runtime's seam
  holds, why upstream's path is harmless today (the only `ToolCallback` bean in this build is upstream's own
  sample), and that it is one `@Bean` away from not being. The gate can only confirm it. What is drawn here
  includes that warning.
-->
<template>
  <ContentWrap :title="t('keelbase.boundary.title')">
    <template #header>
      <el-tag
        v-if="boundary"
        :type="boundary.computed.verdict === 'ok' ? 'success' : 'danger'"
        size="small"
        effect="plain"
      >
        {{
          boundary.computed.verdict === 'ok'
            ? t('keelbase.boundary.verdictOk')
            : t('keelbase.boundary.verdictViolated', { n: boundary.computed.offenders.length })
        }}
      </el-tag>
    </template>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>
    <div v-else-if="missing" class="text-[var(--el-text-color-secondary)]">
      {{ t('keelbase.boundary.missing') }}
    </div>

    <template v-if="boundary">
      <div class="text-12px text-[var(--el-text-color-secondary)] mb-4px">
        {{ t('keelbase.boundary.scope', { files: boundary.computed.scope.filesScanned, roots: boundary.computed.scope.roots.length }) }}
      </div>
      <div class="text-12px text-[var(--el-text-color-secondary)] mb-4px">
        {{ t('keelbase.boundary.reads', { types: boundary.computed.readsTypes.join(' / ') }) }}
      </div>
      <div class="text-12px text-[var(--el-text-color-secondary)] mb-14px">
        {{ t('keelbase.boundary.excluded', { paths: boundary.computed.scope.excludedPaths.join(', ') }) }}
      </div>

      <!-- 运行时扣住的那些 / what the runtime's seam holds -->
      <div class="font-medium mb-4px">{{ t('keelbase.boundary.heldTitle') }}</div>
      <div class="text-12px mb-6px">{{ t('keelbase.boundary.heldWhat') }}</div>
      <el-tag v-for="h in boundary.stated.held.holds" :key="h" size="small" effect="plain" class="mr-6px">
        {{ t(`keelbase.boundary.holds.${h}`) }}
      </el-tag>

      <!-- 它扣不住的 / the path it does not hold -->
      <div class="font-medium mt-16px mb-4px">{{ t('keelbase.boundary.notHeldTitle') }}</div>
      <div class="text-12px mb-4px">{{ t('keelbase.boundary.notHeldWhat') }}</div>
      <div class="text-12px text-[var(--el-text-color-secondary)] mb-6px">{{ t('keelbase.boundary.notHeldWhy') }}</div>
      <el-alert
        v-if="boundary.stated.notHeld.oneBeanAway"
        type="warning"
        :closable="false"
        :title="t('keelbase.boundary.notHeldOneBean')"
      />

      <!-- 会绕过它的 bean：有才画，且是这一页唯一「坏了」的样子 / only when there are any -->
      <template v-if="boundary.computed.offenders.length">
        <div class="font-medium mt-16px mb-6px">{{ t('keelbase.boundary.offendersTitle') }}</div>
        <el-table :data="boundary.computed.offenders" stripe>
          <el-table-column :label="t('keelbase.boundary.colFile')" prop="file" min-width="320" />
          <el-table-column :label="t('keelbase.boundary.colLine')" prop="line" width="90" />
        </el-table>
      </template>
    </template>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContentWrap } from '@/components/ContentWrap'

defineOptions({ name: 'KeelbaseGovernanceBoundary' })

const { t } = useI18n()

/** The record, as the gate writes it. Identifiers and enums only — no prose, in any language. */
/** 那份记录，如门禁所写。只有标识与枚举 —— 任何一种语言的散文都不在里面。 */
type Boundary = {
  schema: string
  computed: {
    scope: { roots: string[]; filesScanned: number; excludedPaths: string[] }
    reads: string
    readsTypes: string[]
    offenders: { file: string; line: number }[]
    verdict: 'ok' | 'violated'
  }
  stated: {
    held: { id: string; holds: string[] }
    notHeld: { id: string; whyHarmlessNow: string; oneBeanAway: boolean }
  }
}

const boundary = ref<Boundary | null>(null)
const error = ref('')
const missing = ref(false)

// The UI build lays this file in from the gate's own `--emit`; nothing here recomputes it. `no-store`
// because a console that showed a remembered boundary would be showing a build that is no longer the one
// running. / 这个文件是 UI 构建用门禁自己的 `--emit` 铺进来的，这里不重算；`no-store`，因为一个显示
// 「记住的」边界的控制台，显示的会是不再在跑的那一版。
const SOURCE = '/keelbase-governance-boundary.json'

onMounted(async () => {
  try {
    const res = await fetch(SOURCE, { cache: 'no-store' })
    // A build without the record is a different fact from a failed read, and it is said differently.
    // 构建里没有这份记录，与「读失败」是两件事，故说的也是两句。
    if (res.status === 404) {
      missing.value = true
      return
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    boundary.value = (await res.json()) as Boundary
  } catch {
    error.value = t('keelbase.boundary.loadFailed')
  }
})
</script>
