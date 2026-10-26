<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  助手面板：**一块对话**。外壳（抽屉）在 `AssistantDrawer.vue`，这里只画内容，故同一块面板将来可以挂在别处。

  **它为什么长这样。** 上一版是「会话列 + 消息列 + 两张卡」三块并排，把主仓那块面板的**信息结构**搬了过来、
  却没搬它的**视觉语法**：没有头像、没有气泡、没有分隔线，卡片里的行直接是一串 `<span>`，于是原始工具名与
  `crm_customer#18` 一类的标识符裸着画出来，还各自撑出一条横向滚动条。这一版按**对话**来组织 —— 会话收进头部
  一个下拉、消息占满中间、要人决定的那一笔**就出现在它产生的地方**（对话尾部一张卡），而不再是一个并排的小格子。

  与主仓那块同名面板的差别，逐条写在这里，因为它们是**本部署**的事实、不是画法：
  **①** 会话来自 `ai1`（`/admin-api/ai1/...`），不是主仓那条；**②** 工具卡只列**写** —— 运行时按调用方
  只供写那一面（`/ai/tool-effects`），读不在这一面，卡片如实写着；**③** 确认卡**在这一页内决定**
  （`/ai/my/confirmations/{token}/decide`，按调用方）—— 治理控制台那几页是**管理员专属**，普通调用方跳过去
  是死路；**④** 入参一律不打印：控制台不打印记录自己的文字（`mask.ts` 那条红线），而本运行时没有给人读的摘要。

  The assistant panel: **one conversation**. The shell (a drawer) is `AssistantDrawer.vue`; this draws only the
  content, so the same panel can be mounted elsewhere later.

  Why it looks like this: the previous version put a session column, a message column and two card columns side
  by side. It had carried over the main repo's **information structure** but not its **visual grammar** — no
  avatar, no bubbles, no separators, and the card rows were bare `<span>`s, so raw tool names and identifiers
  like `crm_customer#18` were drawn as they are, each column pushing out its own horizontal scrollbar. This
  version is organised as a **conversation**: the sessions fold into one select in the head, the messages take
  the body, and the write that needs a person **appears where it was caused** — a card at the tail of the flow.

  How it differs from the main repo's panel of the same name, named because each is a fact about this deployment
  rather than a drawing choice: **①** sessions come from `ai1`, not from the main repo's path; **②** the tool
  face lists **writes only** — the runtime serves the caller's own writes and nothing else; **③** the
  confirmation card **decides in place** (`/ai/my/confirmations/{token}/decide`, caller-scoped) because the
  governance console's pages are administrator-only; **④** arguments are never printed — the console does not
  print a record's own text (`mask.ts`).
-->
<template>
  <div class="assistant-panel">
    <!-- 头部：头像 · 标题 · 会话下拉 · 新会话 -->
    <div class="panel-header">
      <div class="ai-avatar">
        <Icon icon="ri:robot-2-line" :size="20" color="#fff" />
      </div>
      <div class="panel-id">
        <div class="panel-title">AI 助手</div>
        <div class="panel-sub">{{ sessions.length }} 个会话</div>
      </div>
      <el-select
        v-model="sessionId"
        class="session-select"
        size="small"
        placeholder="选择会话"
        :disabled="!sessions.length"
        @change="open"
      >
        <el-option v-for="s in sessions" :key="s.id" :label="maskTitle(s.title)" :value="s.id" />
      </el-select>
      <el-button size="small" :loading="busy" :disabled="!agent.configured" @click="startNew">新会话</el-button>
    </div>

    <div v-if="!agent.configured" class="panel-note">
      本部署还没有配置助手用的 agent（配置项 <code>keelbase.assistant.agent-id</code>）；建会话要它，故先如实说。
    </div>

    <!-- 对话 -->
    <div ref="bodyRef" class="panel-body">
      <div v-if="!messages.length && !streaming && !streamError" class="panel-empty">
        还没有对话。说一句话，助手就能读写这个部署里的业务数据。
      </div>

      <div v-for="m in messages" :key="m.id" class="row" :class="m.role === 'user' ? 'right' : 'left'">
        <div class="bubble" :class="m.role === 'user' ? 'user-bubble' : 'assistant-bubble'">
          <!-- 思考过程默认折起。它是模型自己的一段独白（实测常有「Wait — but I just…」这类自我更正），
               摊出来能盖过它真正给出的那句话 —— 而这块面板要的是**回答**。折起来不等于藏起来：一次点击
               就能看到，而它留在那里仍然是对的（本运行时把推理当可审的东西记着）。 -->
          <!-- The reasoning is folded away by default. It is the model's own monologue — measured, and it
               often contains self-corrections like "Wait — but I just…" — and printed in full it outweighs
               the sentence actually being answered. Folding is not hiding: one click shows it, and keeping
               it is still right, because this runtime treats reasoning as something auditable. -->
          <details v-if="m.reasoning" class="thinking">
            <summary>思考过程</summary>
            <div class="thinking-text">{{ m.reasoning }}</div>
          </details>
          <!-- 助手的话按行内粗体渲染；**用户自己打的字按原样显示** —— 见 `boldSegments` 上方那条注释。 -->
          <!-- The assistant's text is rendered with inline bold; **what the user typed is shown as typed** —
               see the note above `boldSegments`. -->
          <div v-if="m.role === 'user'" class="text">{{ m.content }}</div>
          <div v-else class="text">
            <template v-for="(seg, i) in boldSegments(m.content)" :key="i">
              <strong v-if="seg.bold">{{ seg.text }}</strong>
              <template v-else>{{ seg.text }}</template>
            </template>
          </div>
        </div>
      </div>

      <div v-if="streaming" class="row left">
        <div class="bubble assistant-bubble">
          <div v-if="thinking" class="thinking-live">{{ thinking }}</div>
          <div class="text">
            <template v-for="(seg, i) in boldSegments(reply)" :key="i">
              <strong v-if="seg.bold">{{ seg.text }}</strong>
              <template v-else>{{ seg.text }}</template>
            </template>
            <span class="caret">▌</span>
          </div>
        </div>
      </div>

      <!-- 失败时只给读者一句人话。原始异常（模型的 401 报文、Java 类名、request id）进控制台日志，
           不进这一块：它是维护者看的，而读者要的是「这次没成，我该做什么」。 -->
      <!-- On failure the reader gets one plain sentence. The raw exception (the model's 401 body, its Java
           class name, the request id) goes to the console log, not here: it is for whoever maintains the
           deployment, while the reader needs to know what happened and what to do about it. -->
      <div v-if="streamError" class="panel-error">
        <div>{{ errorHeadline }}</div>
        <div class="panel-error-hint">技术细节已记录在浏览器控制台。</div>
      </div>

      <!-- 待我确认：一笔写悬在这里，等一个人。它出现在对话尾部，因为它就是这一轮产生的。 -->
      <div v-for="c in pending" :key="c.token" class="confirm-card">
        <div class="confirm-head">
          <el-tag size="small" effect="plain" type="warning">待确认</el-tag>
          <el-tag v-if="c.riskLevel" size="small" effect="plain">{{ c.riskLevel }}</el-tag>
          <span class="confirm-title">{{ toolText(c.toolName) }}</span>
        </div>
        <div v-if="c.impact?.actions" class="note">涉及 {{ c.impact.actions }} 个动作。</div>
        <div class="note">入参不在这里显示：控制台不打印记录自己的文字。</div>
        <div class="confirm-actions">
          <el-button size="small" @click="decide(c.token, 'decline')">拒绝</el-button>
          <el-button size="small" type="primary" @click="decide(c.token, 'approve')">批准</el-button>
        </div>
      </div>

      <!-- 我最近的写：本运行时按调用方只供写那一面，读不在这一面。 -->
      <div v-if="effects.length" class="effects">
        <div class="effects-head">我最近的写（{{ effects.length }}）</div>
        <div v-for="e in effects" :key="e.id" class="effect-row">
          <el-tag size="small" effect="plain" :type="outcomeTagType(e.status, false)">
            {{ outcomeText(e.status) }}
          </el-tag>
          <span class="effect-tool">{{ toolText(e.toolName) }}</span>
          <span class="muted">{{ effectRecord(e) }}</span>
          <span v-if="e.revocable" class="muted">可撤销</span>
        </div>
        <div class="note">只列写：本运行时按调用方只供写那一面。</div>
      </div>
    </div>

    <!-- 输入 -->
    <div class="panel-footer">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        :disabled="!sessionId || busy"
        placeholder="对助手说点什么…"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="busy" :disabled="!sessionId" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  assistantApi,
  type AssistantAgent,
  type AssistantMessage,
  type AssistantSession,
} from '@/keelbase/assistantApi'
import { confirmationApi, effectApi, type ConfirmationItem, type EffectRow } from '@/keelbase/governanceApi'
import { maskTitle } from '@/keelbase/mask'
import { outcomeLabelKey, outcomeTagType, toolLabelKey } from '@/keelbase/labels'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

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
const bodyRef = ref<HTMLElement | null>(null)

/**
 * 一行给人读的失败说明。分两句是由**读者能做什么**定的：401 那一类（密钥被拒）只有管理员能处理，
 * 其余失败读者可以稍后重试。原始报文不参与渲染 —— 它含 Java 类名、`request_id`、密钥尾号，
 * 是维护者的材料，已由 `console.error` 记下。
 *
 * One plain line saying what failed, split by **what the reader can do**: a 401-class failure (the key
 * was refused) only an administrator can fix, while any other failure the reader may retry. The raw
 * body never takes part in rendering — it carries a Java class name, a `request_id` and the tail of the
 * key, which are the maintainer's material and are recorded by `console.error` below.
 */
const errorHeadline = computed(() => {
  if (/401|unauthori[sz]ed|invalid[_\s-]?api[_\s-]?key|api key.*invalid/i.test(streamError.value)) {
    return '助手暂时连不上模型服务，请联系管理员检查密钥。'
  }
  return '助手这次没能完成回答，请稍后重试；如果一直如此，请联系管理员检查模型服务的配置。'
})

/** The tool in the reader's language, falling back to the registered name. */
/** 工具名，用读者的语言；不认识就保留注册名。 */
function toolText(name: string | null | undefined): string {
  if (!name) return '—'
  const key = toolLabelKey(name)
  return key ? t(key) : name
}

/** An outcome in the reader's language, falling back to the raw word the runtime used. */
/** 结局，用读者的语言；不认识就保留运行时用的那个词。 */
function outcomeText(outcome: string | null | undefined): string {
  if (!outcome) return '—'
  const key = outcomeLabelKey(outcome)
  return key ? t(key) : outcome
}

/**
 * A piece of the assistant's text, and whether it was marked as bold.
 *
 * 助手的一段文字，以及它有没有被标成粗体。
 */
interface Segment {
  text: string
  bold: boolean
}

/**
 * Splits the assistant's text on `**bold**` runs, so those read as bold instead of as asterisks.
 *
 * **Why not `v-html`.** This text is the model's output — content the deployment does not author and does not
 * control. Handing it to `v-html` would make every reply an HTML injection surface, and escaping it first is a
 * second thing to get right. Instead the text is only ever *split*, and the pieces are rendered as ordinary
 * Vue children: there is no HTML parsing anywhere on this path, so there is nothing to inject into.
 *
 * **Only the assistant's side.** What the user typed is shown as typed — turning their own `**` into bold would
 * be this panel editing their words back at them. The model's output is the only thing here that arrives with
 * markdown in it.
 *
 * **An unterminated `**` stays literal**, which is what streaming looks like: while the model is still emitting
 * a bold run, the opening marker is not yet paired, so it shows as a marker for a moment and settles. Pairing
 * it early would mean guessing where a run ends.
 *
 * 把助手的文字按 `**粗体**` 切开，好让它们读作粗体、而不是星号。
 *
 * **为什么不用 `v-html`。** 这段文字是**模型的输出** —— 本部署既不是它的作者、也控制不了它。把它交给
 * `v-html`，等于让每条回复都成为一处 HTML 注入面；而先转义再交出去，是多一件要做对的事。这里只做**切分**，
 * 各段按普通 Vue 子节点渲染：这条路径上**没有任何 HTML 解析**，也就没有可注入的地方。
 *
 * **只管助手那一侧。** 用户自己打的字按原样显示 —— 把他们的 `**` 变成粗体，是本面板在替他们改自己的话。
 * 这里唯一会带着 markdown 来的，是模型的输出。
 *
 * **没配对的 `**` 保持字面**，而那正是流式过程中的样子：模型还在往外吐一段粗体时，起始标记尚未配对，于是它
 * 先像个标记、随后落定。提前配对它，等于去猜那一段在哪儿结束。
 */
function boldSegments(text: string | null | undefined): Segment[] {
  if (!text) return []
  const segments: Segment[] = []
  const pattern = /\*\*([\s\S]+?)\*\*/g
  let end = 0
  for (const match of text.matchAll(pattern)) {
    if (match.index > end) segments.push({ text: text.slice(end, match.index), bold: false })
    segments.push({ text: match[1], bold: true })
    end = match.index + match[0].length
  }
  if (end < text.length) segments.push({ text: text.slice(end), bold: false })
  return segments
}

/**
 * The record a write landed on, one line: what it is, which one, and — when the ledger carried one —
 * its masked title.
 *
 * **Why this does not ask the host.** The governance pages do ask (`resolveRecordName`), and that is
 * right for them: they are administrator-only. This panel is the *ordinary* caller's — the `ai1` grant
 * exists so a non-administrator can use the assistant — and the host's CRM directory needs a CRM
 * permission that caller may simply not hold. Asking anyway is not harmless: the console's axios layer
 * turns each refusal into a visible error toast, so a caller without `crm:customer:query` would see one
 * error per listed write, on a page that is working correctly. Measured on this deployment: an ordinary
 * caller opened the drawer and got four 「没有该操作权限」 toasts for the four rows drawn. The ledger's
 * own fields need no permission and are what this panel draws from.
 *
 * 一次写落在哪条记录上，一行说完：是什么、哪一条，以及（台账带了的时候）**掩码后的**标题。
 *
 * **为什么这里不问宿主。** 治理那几页确实会问（`resolveRecordName`），对它们是**对的**：那几页是管理员专属。
 * 而这一块是**普通调用方**的——`ai1` 那条授权正是为了让非管理员能用助手——而宿主的 CRM 名录要一条**那个调用
 * 方未必持有**的 CRM 权限。**问了不是无害的**：控制台的 axios 层把每次拒绝弹成一条可见的错误提示，于是一个没有
 * `crm:customer:query` 的调用方会在一个**本身工作正常**的页面上、每条被列出的写看到一条错误。本部署实测：普通
 * 调用方打开抽屉，为画出的四行拿到四条「没有该操作权限」。台账自己的字段不需要任何权限，本面板就画它。
 */
function effectRecord(e: EffectRow): string {
  const parts = [e.resultType || '—', `#${e.resultId ?? '—'}`]
  if (e.targetTitle) parts.push(maskTitle(e.targetTitle))
  return parts.join(' · ')
}

/** Scroll the newest message into view — a chat that does not is a chat you have to scroll by hand. */
/** 把最新一条滚进视野 —— 不这么做的对话，等于要人自己往下拉。 */
async function scrollToEnd() {
  await nextTick()
  const el = bodyRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 两张卡的数据，跑完一轮要重取——写正是在那一轮里落下的。 */
/** The two cards' data; re-read after a turn, because that is when a write lands. */
async function loadCards() {
  // `'pending'` 是**要说出来的**：不带它时这条面回的是调用方**全部**的确认（已批的、已拒的、超时的都在），
  // 于是「待我确认」那张卡会把一条**已经决过**的记录也画出来，还配着可点的批准/拒绝。实测本部署：
  // 一次拒绝之后卡片照旧在原处、两个按钮照旧亮着。门禁脚本自己用的就是 `?status=pending`。
  //
  // `'pending'` has to be said: without it this face answers with **every** confirmation the caller has —
  // approved, declined and timed out alike — so the 「待我确认」 card drew an already-decided row, buttons
  // and all. Measured on this deployment: after a decline the card stayed put with both buttons live. The
  // gate script passes `?status=pending` for the same reason.
  const [written, waiting] = await Promise.all([effectApi.list(1, 10), confirmationApi.mine('pending')])
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
  await scrollToEnd()
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
      else if (event.event === 'error') {
        streamError.value = event.data
        console.error('[assistant] the model stream reported an error:', event.data)
      }
      scrollToEnd()
    })
  } catch (e) {
    const raw = e instanceof Error ? e.message : String(e)
    streamError.value = raw
    console.error('[assistant] send failed:', raw)
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
  min-height: 0;
  font-size: 13px;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ai-avatar {
  width: 38px;
  height: 38px;
  border-radius: var(--keel-radius-md, 10px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: linear-gradient(
    135deg,
    var(--keel-brand-gradient-from, var(--el-color-primary)) 0%,
    var(--keel-brand-gradient-to, var(--el-color-primary)) 100%
  );
  box-shadow: 0 4px 12px var(--keel-glow, rgba(79, 70, 229, 0.3));
}
.panel-id {
  flex: 1;
  min-width: 0;
}
.panel-title {
  font-weight: 600;
  font-size: 15px;
  line-height: 1.3;
}
.panel-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.session-select {
  width: 150px;
  flex-shrink: 0;
}
.panel-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 2px;
}
.row {
  display: flex;
  margin-bottom: 10px;
}
.row.left {
  justify-content: flex-start;
}
.row.right {
  justify-content: flex-end;
}
.bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;
}
.user-bubble {
  background: var(--el-color-primary);
  color: #fff;
  border-top-right-radius: 4px;
}
.assistant-bubble {
  background: var(--el-fill-color-light);
  border-top-left-radius: 4px;
}
.thinking {
  margin-bottom: 6px;
}
.thinking > summary {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  list-style: none;
  user-select: none;
}
.thinking > summary::-webkit-details-marker {
  display: none;
}
.thinking > summary::before {
  content: '▸ ';
}
.thinking[open] > summary::before {
  content: '▾ ';
}
.thinking-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  border-left: 2px solid var(--el-border-color);
  padding-left: 6px;
  margin-top: 4px;
  white-space: pre-wrap;
}
.thinking-live {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  border-left: 2px solid var(--el-border-color);
  padding-left: 6px;
  margin-bottom: 4px;
  white-space: pre-wrap;
}
.confirm-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  margin-top: 12px;
  background: var(--el-bg-color);
}
.confirm-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.confirm-title {
  font-weight: 600;
}
.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.effects {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.effects-head {
  font-weight: 600;
  margin-bottom: 6px;
}
.effect-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
  line-height: 1.6;
}
.effect-tool {
  flex-shrink: 0;
}
.note,
.panel-note,
.panel-empty,
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.panel-note {
  padding-top: 10px;
  line-height: 1.6;
}
.panel-empty {
  padding: 24px 8px;
  text-align: center;
  line-height: 1.6;
}
.panel-error {
  color: var(--el-color-danger);
  padding: 4px 2px;
  line-height: 1.6;
}
.panel-error-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.panel-footer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
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
