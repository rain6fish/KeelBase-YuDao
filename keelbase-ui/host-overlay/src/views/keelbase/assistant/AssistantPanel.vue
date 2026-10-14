<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  助手面板：会话 / 消息 / 工具卡 / 确认卡 / 输入区。**宿主无关**——外壳（抽屉）在 `AssistantDrawer.vue`，
  这里只画内容，故同一块面板将来可以挂在别处。

  与主仓那块同名面板的差别，逐条写在这里，因为它们是**本部署**的事实、不是画法：
  **①** 会话来自 `ai1`（`/admin-api/ai1/...`），不是主仓那条；**②** 工具卡只列**写** —— 运行时按调用方
  只供写那一面（`/ai/tool-effects`），读不在这一面，卡片如实写着；**③** 确认卡**在这一页内决定**
  （`/ai/my/confirmations/{token}/decide`，按调用方）—— 治理控制台那几页是**管理员专属**，普通调用方跳过去
  是死路；**④** 入参一律不打印：控制台不打印记录自己的文字（`mask.ts` 那条红线），而本运行时没有给人读的摘要。

  The assistant panel: sessions / messages / tool card / confirmation card / input. **Host-agnostic** — the
  shell (a drawer) is `AssistantDrawer.vue`; this draws only the content.

  How it differs from the main repo's panel of the same name, named because each is a fact about this
  deployment rather than a drawing choice: **①** sessions come from `ai1`, not from the main repo's path;
  **②** the tool card lists **writes only** — the runtime serves the caller's own writes and nothing else,
  and the card says so; **③** the confirmation card **decides in place** (`/ai/my/confirmations/{token}/decide`,
  caller-scoped) because the governance console's pages are administrator-only and a jump there would be a
  dead end for an ordinary caller; **④** arguments are never printed — the console does not print a record's
  own text (`mask.ts`), and this runtime has no human-readable summary to print instead.
-->
<template>
  <div class="assistant-panel">
    <div class="panel-head">
      <span class="panel-title">AI 助手</span>
      <el-button size="small" :loading="busy" :disabled="!agent.configured" @click="startNew">新会话</el-button>
    </div>

    <div v-if="!agent.configured" class="panel-note">
      本部署还没有配置助手用的 agent（配置项 <code>keelbase.assistant.agent-id</code>）；建会话要它，故先如实说。
    </div>

    <div class="panel-body">
      <div class="sessions">
        <div
          v-for="s in sessions"
          :key="s.id"
          :class="['session', { active: s.id === sessionId }]"
          @click="open(s.id)"
        >
          {{ maskTitle(s.title) }}
        </div>
        <div v-if="sessions.length === 0" class="panel-empty">还没有会话</div>
      </div>

      <div class="messages">
        <div v-for="m in messages" :key="m.id" :class="['msg', m.role === 'user' ? 'from-user' : 'from-ai']">
          <div v-if="m.reasoning" class="msg-thinking">{{ m.reasoning }}</div>
          <div class="msg-text">{{ m.content }}</div>
        </div>
        <div v-if="streaming" class="msg from-ai">
          <div v-if="thinking" class="msg-thinking">{{ thinking }}</div>
          <div class="msg-text">{{ reply }}<span class="caret">▌</span></div>
        </div>
        <div v-if="streamError" class="panel-error">{{ streamError }}</div>
      </div>
    </div>

    <div class="cards">
      <div class="card">
        <div class="card-head">我最近的写（{{ effects.length }}）</div>
        <div v-for="e in effects" :key="e.id" class="card-row">
          <span>{{ e.toolName }}</span>
          <span class="muted">{{ e.resultType }}#{{ e.resultId }} · {{ e.status }}</span>
        </div>
        <div v-if="effects.length === 0" class="panel-empty">这一位还没有写</div>
        <div class="card-note">只列**写**：本运行时按调用方只供写那一面，读不在这一面。</div>
      </div>

      <div class="card">
        <div class="card-head">待我确认（{{ pending.length }}）</div>
        <div v-for="c in pending" :key="c.token" class="card-row">
          <span>{{ c.toolName }}</span>
          <span class="muted">{{ c.riskLevel }}</span>
          <el-button size="small" type="primary" @click="decide(c.token, 'approve')">批准</el-button>
          <el-button size="small" @click="decide(c.token, 'decline')">拒绝</el-button>
        </div>
        <div v-if="pending.length === 0" class="panel-empty">没有等你决定的</div>
        <div class="card-note">入参不在这里显示：控制台不打印记录自己的文字。</div>
      </div>
    </div>

    <div class="panel-input">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        :disabled="!sessionId || busy"
        placeholder="对助手说点什么…"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="busy" :disabled="!sessionId" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  assistantApi,
  type AssistantAgent,
  type AssistantMessage,
  type AssistantSession,
} from '@/keelbase/assistantApi'
import { confirmationApi, effectApi, type ConfirmationItem, type EffectRow } from '@/keelbase/governanceApi'
import { maskTitle } from '@/keelbase/mask'

const agent = ref<AssistantAgent>({ configured: false, agentId: null })
const sessions = ref<AssistantSession[]>([])
const sessionId = ref<number | null>(null)
const messages = ref<AssistantMessage[]>([])
const effects = ref<EffectRow[]>([])
const pending = ref<ConfirmationItem[]>([])
const draft = ref('')
const busy = ref(false)
const streaming = ref(false)
const thinking = ref('')
const reply = ref('')
const streamError = ref('')

/** 两张卡的数据，跑完一轮要重取——写正是在那一轮里落下的。 */
/** The two cards' data; re-read after a turn, because that is when a write lands. */
async function loadCards() {
  const [written, waiting] = await Promise.all([effectApi.list(1, 10), confirmationApi.mine()])
  effects.value = written.items ?? []
  pending.value = waiting
}

async function loadSessions() {
  if (!agent.value.agentId) {
    sessions.value = []
    return
  }
  sessions.value = await assistantApi.mySessions(Number(agent.value.agentId))
}

async function open(id: number) {
  sessionId.value = id
  messages.value = await assistantApi.messages(id)
  // `streamError` is deliberately **not** cleared here. `send()` ends by calling this to re-read the
  // transcript, so clearing it here wiped the error the send had just recorded — measured: a send that
  // never reached the network showed no error at all, and the page looked like it had simply done
  // nothing. The error is cleared where a send starts, which is where a stale one belongs.
  // 这里**刻意不**清 `streamError`。`send()` 结尾会调它重读一次记录，于是清在这里会**抹掉那次发送刚记下的错误**
  // —— 实测：一次根本没走到网络的发送**一个错都不显示**，页面看上去只是「什么都没做」。错误在**一次发送开始时**清，
  // 那才是陈旧错误该被清掉的地方。
}

async function startNew() {
  if (!agent.value.agentId) return
  busy.value = true
  try {
    const id = await assistantApi.createSession(Number(agent.value.agentId))
    await loadSessions()
    await open(id)
  } finally {
    busy.value = false
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || sessionId.value === null) return
  draft.value = ''
  streamError.value = ''
  thinking.value = ''
  reply.value = ''
  busy.value = true
  streaming.value = true
  try {
    await assistantApi.sendStream(sessionId.value, content, (event) => {
      if (event.event === 'thinking') thinking.value += event.data
      else if (event.event === 'message') reply.value += event.data
      else if (event.event === 'error') streamError.value = event.data
    })
  } catch (e) {
    streamError.value = e instanceof Error ? e.message : String(e)
  } finally {
    streaming.value = false
    busy.value = false
    await open(sessionId.value)
    await loadCards()
  }
}

async function decide(token: string, decision: 'approve' | 'decline') {
  try {
    await confirmationApi.decide(token, decision)
    ElMessage.success(decision === 'approve' ? '已批准' : '已拒绝')
    await loadCards()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(async () => {
  agent.value = await assistantApi.agent()
  await Promise.all([loadSessions(), loadCards()])
  const first = sessions.value[0]
  if (first) await open(first.id)
})
</script>

<style scoped>
.assistant-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
  font-size: 13px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  font-size: 15px;
}
.panel-body {
  display: flex;
  gap: 8px;
  min-height: 200px;
  flex: 1;
}
.sessions {
  width: 130px;
  overflow: auto;
  border-right: 1px solid var(--el-border-color-lighter);
}
.session {
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 4px;
}
.session.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.messages {
  flex: 1;
  overflow: auto;
}
.msg {
  margin-bottom: 10px;
  white-space: pre-wrap;
  word-break: break-word;
}
.from-user .msg-text {
  color: var(--el-color-primary);
}
.msg-thinking {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  border-left: 2px solid var(--el-border-color);
  padding-left: 6px;
  margin-bottom: 4px;
  white-space: pre-wrap;
}
.cards {
  display: flex;
  gap: 8px;
}
.card {
  flex: 1;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 8px;
  max-height: 160px;
  overflow: auto;
}
.card-head {
  font-weight: 600;
  margin-bottom: 4px;
}
.card-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}
.card-note,
.panel-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.panel-error {
  color: var(--el-color-danger);
}
.panel-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.panel-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.caret {
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
</style>
