<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  A page-level note for the assistant console: what this page is for, and what each of its fields does.

  Upstream ships these pages without a word of explanation — the field labels are the only text, and
  several of them do not say enough on their own (`模型标识` could be a display name; it is in fact the
  string sent to the model service). This component is mounted from `0003-assistant-page-notes.patch`,
  which adds one line per page; the prose lives here and only here, so a wording change is one edit.

  Compact mode exists for the session page: it is a full-height chat layout with no room for a block
  above the content, so its note goes inline in the header instead.

  助手管理台的页面级说明：这一页是干什么的，以及它的每个字段分别管什么。

  上游把这些页面交出来时一个字说明都没有——字段标签就是全部文字，而其中几个光看标签不够（`模型标识` 看着
  像显示名，实际是**发给模型服务的那串名字**）。本组件由 `0003-assistant-page-notes.patch` 挂上去，每页一行；
  文字只住在这里一处，改措辞只改这里。

  紧凑模式是给会话页的：那是一套全高的聊天布局，内容之上没有放整块提示的地方，故它的说明改放标题栏里一行。
-->
<template>
  <el-alert
    v-if="!compact"
    class="mb-16px"
    type="info"
    :closable="false"
    show-icon
  >
    <template #title>
      <span class="font-600">{{ note.title }}</span>
    </template>
    <p class="mt-4px mb-0"><template v-for="(s, i) in segments(note.summary)" :key="i"><strong v-if="s.b">{{ s.t }}</strong><span v-else>{{ s.t }}</span></template></p>
    <ul v-if="note.fields?.length" class="mt-6px mb-0 pl-18px">
      <li v-for="f in note.fields" :key="f.k">
        <span class="font-600">{{ f.k }}</span>：<template v-for="(s, i) in segments(f.v)" :key="i"><strong v-if="s.b">{{ s.t }}</strong><span v-else>{{ s.t }}</span></template>
      </li>
    </ul>
  </el-alert>
  <span v-else class="text-13px text-[var(--el-text-color-secondary)] truncate">
    {{ note.title }} — {{ note.summary }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * Splits on `**…**` so the emphasis renders as `<strong>` rather than as literal asterisks — the prose
 * carries the same markers as everywhere else in this repository, and Vue interpolation would otherwise
 * print them.
 *
 * Returned as data rather than as a component on purpose: the template renders the parts with plain
 * `<strong>` / `<span>`, so there is no second component to resolve. (A `<script setup>` arrow function
 * used as `<RichText>` looked right and compiled, but the host's build dropped the whole block at
 * runtime — measured, not guessed.)
 *
 * 把 `**…**` 拆开，好让强调渲染成 `<strong>` 而不是原样打出星号——这些文字带着本仓各处一致的标记，而 Vue
 * 的插值会把它照字面打出来。
 *
 * 刻意**返回数据、不返回组件**：模板用朴素的 `<strong>` / `<span>` 渲染这些片段，于是没有第二个组件需要解析。
 *（把 `<script setup>` 里的箭头函数当 `<RichText>` 用，看着对、也编译得过，但本宿主的构建在运行时整块丢掉——
 *  这是实测的，不是猜的。）
 */
const segments = (text?: string) =>
  (text ?? '').split(/\*\*(.+?)\*\*/g).map((t, i) => ({ t, b: i % 2 === 1 }))

type Field = { k: string; v: string }
type Note = { title: string; summary: string; fields?: Field[] }

const props = defineProps<{ page: string; compact?: boolean }>()

const NOTES: Record<string, Note> = {
  home: {
    title: '助手被用成了什么样',
    summary:
      '这一页只读：会话与消息的量、按天的趋势、各智能体各被用了多少次。要改配置，去「供应商 / 模型 / 智能体」。',
    fields: [
      { k: '会话 / 消息计数', v: '所选时间范围内，使用者与助手之间发生过的会话数与消息数' },
      { k: '消息趋势', v: '按天统计的消息量——看它有没有被真的用起来' },
      { k: '智能体分布', v: '每个智能体各被用了多少次，用来判断哪个角色真有人用' },
    ],
  },
  provider: {
    title: '供应商：连哪个模型服务',
    summary:
      '一条供应商 = 一个模型服务的接入点。助手要跟模型说话，先得有这一条。它只说「连哪里、用哪把钥匙」，不含模型名——模型名在它下面那条「模型」里。',
    fields: [
      { k: '模型供应商', v: '这条接入点的名字，随你起，只给自己认' },
      {
        k: '接口地址',
        v: '模型服务的根 URL，走 OpenAI 兼容格式（本部署用 https://api.deepseek.com）',
      },
      {
        k: 'API 密钥',
        v: '那把 key。可以填字面值，也可以填 ${DEEPSEEK_API_KEY} 这类占位符，由宿主启动时从环境变量解析——本部署用的就是占位符，故这里看到的是变量名、不是真 key',
      },
      { k: '请求 Header', v: '要额外带的请求头（键值对）；值里可用 {session} 占位' },
      { k: '状态', v: '关掉它，挂在它下面的模型与智能体一并不可用' },
    ],
  },
  model: {
    title: '模型：用哪一个',
    summary:
      '一个供应商下可用的模型清单，也就是「助手跑在哪个模型上」的最终所在。从「供应商」那一行点进来。',
    fields: [
      { k: '模型名称', v: '给你自己看的名字' },
      {
        k: '模型标识',
        v: '**真正发给模型服务的那串名字**（本部署是 deepseek-v4-flash）——要换模型，改的就是它',
      },
      { k: '模型类型', v: '对话 / 向量化等；决定它能被哪种用途选中。对话模型与向量化模型是两回事' },
      { k: '状态', v: '关掉它，指向它的智能体就不再能用它' },
    ],
  },
  agent: {
    title: '智能体：助手本身',
    summary:
      '一个智能体 = 一套指令 + 一个模型 + 一组它能用的东西。控制台那条助手，就是这里的「控制台助手」。',
    fields: [
      { k: 'Agent 名称 / 介绍', v: '名字与一句话说明' },
      {
        k: '模型供应商 / 对话模型',
        v: '**这个智能体跑在哪个模型上**。两级连选：先选供应商，再选它下面的模型',
      },
      { k: '系统指令', v: '每次会话都带上的一段提示词，决定它的角色与说话方式' },
      { k: '关联知识库', v: '它能查的资料（见「知识库」页）' },
      { k: '关联 MCP', v: '它能调的外部工具服务器（见「MCP」页）' },
      { k: '关联 SKILL', v: '它可复用的技能包（见「技能」页）' },
    ],
  },
  knowledge: {
    title: '知识库：给助手查的资料',
    summary:
      '文档进来先切成块，再由「向量化模型」转成向量存下；提问时按相近度取回相关的块。',
    fields: [
      { k: '知识库名称 / 描述', v: '名字与一句话说明' },
      {
        k: '向量化模型供应商 / 向量化模型',
        v: '用哪个模型把文档转成向量。**注意它与「对话模型」是两回事**——这里要选向量化类型的模型，不是对话那个',
      },
      { k: '状态', v: '关掉后，关联了它的智能体就查不到资料' },
    ],
  },
  mcp: {
    title: 'MCP：外部工具服务器',
    summary:
      '接上之后，那台服务器上的工具会成为智能体可用的工具——而且**照旧过治理层**：风险分级、写操作确认、审计与撤销一样不少。',
    fields: [
      { k: 'MCP 名称', v: '这条接入的名字' },
      { k: '服务地址', v: '工具服务器的地址' },
      { k: '传输方式', v: '用什么协议连它（如 stdio / HTTP）' },
      { k: '状态', v: '关掉后，关联了它的智能体就用不到那些工具' },
    ],
  },
  skill: {
    title: '技能：可复用的技能包',
    summary:
      '一段说明加一组文件。挂到智能体上（见「智能体」页的「关联 SKILL」）之后，助手就能按需读取这套做法，不必每次在系统指令里重写一遍。',
    fields: [
      { k: 'SKILL 名称 / SKILL 描述', v: '名字与说明——描述写清楚它什么时候该被用上' },
      { k: '版本', v: '这套技能的版本号' },
      { k: '状态', v: '关掉后，关联了它的智能体就不再带上它' },
    ],
  },
  session: {
    title: '会话：在这里试助手',
    summary:
      '左侧是会话列表（按智能体分组），右侧是对话。想确认「换的那个模型生效了没有」，在这里问一句最快。',
    fields: [
      { k: '输入框', v: 'Enter 发送，Shift+Enter 换行' },
      { k: '左侧', v: '按智能体分组的历史会话；点上面的折叠图标可收起' },
    ],
  },
}

const note = computed<Note>(() => NOTES[props.page] ?? { title: '', summary: '' })
</script>
