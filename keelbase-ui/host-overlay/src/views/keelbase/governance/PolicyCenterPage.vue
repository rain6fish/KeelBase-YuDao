<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What governance is doing to each tool, and how much of the audit is kept — the one page in this
  console that writes.

  Two things shape it. First, an override is a **narrow** document: a tool's mode, whether it is
  enabled, and who may call it. That is the whole of what this runtime validates, and it *refuses*
  anything else rather than storing it — so the page offers those three and nothing more, because a
  control the runtime would reject is not a control.

  Second, saving replaces the **whole** document. So each override is held as the object it came back
  as and only the edited fields are touched: a key this page does not draw (the object declares one
  more) survives the round trip instead of being quietly dropped. "Reset" removes the tool's override
  entirely, which is a different thing from setting it to the default.

  治理对每个工具在做什么，以及审计留到什么粒度 —— 本控制台里**唯一会写**的一页。

  两件事决定了它的形状。其一，一条覆盖是一份**很窄**的文档：某个工具的 mode、它是否启用、以及谁可以
  调它。这就是本运行时**校验的全部**，其余它**拒绝**而不是存下来 —— 故页面只提供这三样，别的都不给：
  一个运行时会拒掉的控件，不是一个控件。

  其二，保存替换的是**整份**文档。故每条覆盖都按**它回来的那个对象**持有，只动被编辑的那几个键：本页
  不画的那个键（对象还声明了另一个）会在往返中原样活下来，而不是被悄悄丢掉。「恢复默认」是把该工具的
  覆盖**整条删掉**，这与「把它设成默认值」不是一回事。
-->
<template>
  <ContentWrap :title="t('keelbase.policy.title')">
    <template #header>
      <el-tag v-if="revision" size="small" effect="plain">{{ t('keelbase.policy.revision', { r: revision }) }}</el-tag>
      <el-button class="ml-auto" size="small" :loading="loading" @click="load">
        {{ t('keelbase.common.refresh') }}
      </el-button>
      <el-button size="small" type="primary" :loading="saving" :disabled="!dirty" @click="save">
        {{ t('keelbase.policy.save') }}
      </el-button>
    </template>

    <!-- Said on the page: what this document can and cannot say, and that saving replaces all of it. -->
    <!-- 写在页面上：这份文档能说什么、不能说什么，以及保存会替换掉它的全部。 -->
    <div class="note">{{ t('keelbase.policy.note') }}</div>
    <!-- 覆盖声明（`D57`）：这一页读到哪、到不了哪些路径 —— 只写事实。 -->
    <!-- Coverage statement (`D57`): what this page reads, and which paths it cannot reach. -->
    <div class="note">{{ t('keelbase.coverage.policy') }}</div>

    <div v-if="error" class="text-[var(--el-color-danger)] mb-10px">{{ error }}</div>

    <div class="granularity">
      <span>{{ t('keelbase.policy.granularity') }}</span>
      <el-radio-group v-model="granularity" size="small">
        <el-radio value="all">{{ t('keelbase.policy.granularityAll') }}</el-radio>
        <el-radio value="write">{{ t('keelbase.policy.granularityWrite') }}</el-radio>
        <el-radio value="off">{{ t('keelbase.policy.granularityOff') }}</el-radio>
      </el-radio-group>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column :label="t('keelbase.toolInv.colTool')" min-width="320">
        <template #default="{ row }">
          <!-- 业务读者认得那个中文名，工程师仍看得见注册名：两者都给，不让谁给谁让路。 -->
          <div>{{ toolText(row.name) }} <el-tag v-if="row.override" size="small" type="warning" effect="plain">{{ t('keelbase.policy.overrideTag') }}</el-tag></div>
          <div class="muted">{{ row.name }}</div>
          <!-- 一行**读者语言**的用途：这张表是要被人读着做决定的，不该逼人先认得工具名。 -->
          <div class="muted">{{ toolDescription(row.name, row.description) }}</div>
          <!-- 工具**自己**说它做什么 —— 是它的声明，不是我们对它的概括（与 AI 审计页同一口径）。 -->
          <div v-if="row.description" class="muted">{{ t('keelbase.policy.toolDeclared', { text: row.description }) }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colRisk')" width="120">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.riskLevel }}</el-tag></template>
      </el-table-column>
      <!-- The gate is the declaration from the inventory, but the word shown is in the reader's
           language; "default" means this policy does not override it. -->
      <!-- 门槛取的是清单里的声明，但显示的是**读者语言**的词；「用默认」＝这份策略没有覆盖它。 -->
      <el-table-column :label="t('keelbase.toolInv.colGate')" width="180">
        <template #default="{ row }">
          <el-select :model-value="row.mode || USE_DEFAULT" size="small" class="!w-160px" @update:model-value="(v: string) => setMode(row, v)">
            <!-- The "default" arm carries a real value, not an empty string: el-select reads `''` as
                 "nothing chosen" and shows the placeholder, so the effective gate would be invisible —
                 which is the one thing this column exists to show. -->
            <!-- 「用默认」那一项带的是**真取值**、不是空串：el-select 把 `''` 读成「什么都没选」并显示占位符，
                 那样**生效的门槛就看不见了** —— 而这一列存在的唯一理由就是让人看见它。 -->
            <el-option :label="`${t('keelbase.policy.useDefault')}（${gateText(row.gateMode)}）`" :value="USE_DEFAULT" />
            <el-option :label="t('keelbase.vocab.gate.auto')" value="auto" />
            <el-option :label="t('keelbase.vocab.gate.confirm')" value="confirm" />
            <el-option :label="t('keelbase.vocab.gate.approval')" value="approval" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.policy.colEnabled')" width="110" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @update:model-value="(v: boolean) => setEnabled(row, v)" />
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.toolInv.colRoles')" width="200">
        <template #default="{ row }">
          <el-select
            :model-value="row.allowedRoles"
            multiple
            clearable
            size="small"
            :placeholder="t('keelbase.policy.useDefault')"
            @update:model-value="(v: string[]) => setRoles(row, v)"
          >
            <el-option :label="t('keelbase.policy.roleAdmin')" value="admin" />
            <el-option :label="t('keelbase.policy.roleUser')" value="user" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column :label="t('keelbase.policy.colReset')" width="110" align="center">
        <template #default="{ row }">
          <el-button v-if="row.override" link type="danger" @click="resetRow(row)">
            {{ t('keelbase.policy.resetRow') }}
          </el-button>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
  </ContentWrap>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ContentWrap } from '@/components/ContentWrap'
import {
  policyApi,
  toolApi,
  type GovernancePolicy,
  type ToolInventoryItem,
  type ToolPolicyOverride,
} from '@/keelbase/governanceApi'
import { runtimeErrorMessage } from '@/keelbase/runtime'
import { gateLabelKey, toolDescriptionKey, toolLabelKey } from '@/keelbase/labels'

defineOptions({ name: 'KeelbaseGovernancePolicyCenter' })

const { t } = useI18n()

/** A real value for the "no override" arm — see the select's comment for why it is not `''`. */
/** 「不覆盖」那一项的真取值 —— 为什么不是 `''` 见那个 select 的注释。 */
const USE_DEFAULT = '__default__'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const revision = ref<string | number>('')

const inventory = ref<ToolInventoryItem[]>([])
const granularity = ref('all')
/**
 * The overrides exactly as the face returned them, and the only thing saved. Held as the objects they
 * came back as on purpose: the page edits three of their keys and must not drop the fourth.
 *
 * 覆盖，**按那条面回给它的原样**持有，也是唯一会被保存的东西。刻意按回来的对象持有：页面只编辑其中
 * 三个键，**不能**把第四个丢掉。
 */
const overrides = ref<Record<string, ToolPolicyOverride>>({})

/** The saved state, to tell whether the page has anything to write. */
/** 已保存的状态，用来判断页面有没有东西要写。 */
const saved = ref('')

const snapshot = () => JSON.stringify({ tools: overrides.value, audit: { granularity: granularity.value } })
const dirty = computed(() => snapshot() !== saved.value && saved.value !== '')

interface Row extends ToolInventoryItem {
  override: ToolPolicyOverride | null
  mode: string
  enabled: boolean
  allowedRoles: string[]
}

const rows = computed<Row[]>(() =>
  inventory.value.map((toolItem) => {
    const held = overrides.value[toolItem.name] ?? null
    return {
      ...toolItem,
      override: held,
      mode: held?.mode ?? '',
      enabled: held?.enabled ?? true,
      allowedRoles: held?.allowedRoles ?? [],
    }
  }),
)

function gateText(gate: string | null | undefined): string {
  const key = gateLabelKey(gate)
  return key ? t(key) : (gate ?? '—')
}

/** 工具名，用读者的语言；控制台不认识它时保留注册名（诚实，不是编造）。 */
function toolText(name: string): string {
  const key = toolLabelKey(name)
  return key ? t(key) : name
}

/** 工具的一行用途，用读者的语言；控制台不认识它时回落到**工具自己声明**的那句。 */
function toolDescription(name: string, declared: string | null): string {
  const key = toolDescriptionKey(name)
  return key ? t(key) : (declared ?? '—')
}

/** Every edit goes through here, so an override that was there stays there. */
/** 每次编辑都走这里，故「本来就在的覆盖」**仍在**。 */
function edit(row: Row, patch: ToolPolicyOverride) {
  overrides.value = { ...overrides.value, [row.name]: { ...(overrides.value[row.name] ?? {}), ...patch } }
}

function setMode(row: Row, mode: string) {
  if (mode === USE_DEFAULT) {
    // Back to the declared gate: remove the mode from the override; the rest of it stays.
    // 回到声明的闸：把 mode 从这条覆盖里去掉；这条覆盖的其余部分留着。
    const held = { ...(overrides.value[row.name] ?? {}) }
    delete held.mode
    overrides.value = { ...overrides.value, [row.name]: held }
    return
  }
  edit(row, { mode })
}

function setEnabled(row: Row, enabled: boolean) {
  edit(row, { enabled })
}

function setRoles(row: Row, allowedRoles: string[]) {
  edit(row, { allowedRoles })
}

function resetRow(row: Row) {
  const next = { ...overrides.value }
  delete next[row.name]
  overrides.value = next
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [policy, tools] = await Promise.all([policyApi.read(), toolApi.inventory()])
    apply(policy)
    inventory.value = tools
  } catch (e) {
    inventory.value = []
    error.value = runtimeErrorMessage(e, t('keelbase.common.loadFailed'))
  } finally {
    loading.value = false
  }
}

function apply(policy: GovernancePolicy) {
  overrides.value = { ...(policy.tools ?? {}) }
  granularity.value = policy.audit?.granularity ?? 'all'
  revision.value = policy.revision ?? ''
  saved.value = snapshot()
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    const policy = await policyApi.write({ tools: overrides.value, audit: { granularity: granularity.value } })
    apply(policy)
    ElMessage.success(t('keelbase.policy.saved'))
  } catch (e) {
    error.value = runtimeErrorMessage(e, t('keelbase.policy.saveFailed'))
    // The face refuses rather than applies, so nothing changed; re-read to be sure of what is stored.
    // 那条面是**拒绝**而不是施加，故什么都没变；重读一次以确保显示的就是库里那份。
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.note {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.granularity {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
}
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
