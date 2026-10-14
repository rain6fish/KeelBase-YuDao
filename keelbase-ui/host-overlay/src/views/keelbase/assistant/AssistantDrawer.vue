<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  助手的**外壳**：右侧滑出的抽屉。内容全在 `AssistantPanel.vue`，本文件只负责抽屉本身与开合。

  形状照主仓（`Web-Admin-Vue/src/components/AiAssistantDrawer.vue`）：`direction="rtl"` 从右滑入、
  宽度 `min(600px, 92vw)`（窄屏上不撑满），开合走 `v-model`。入口不在这里 —— 它挂在宿主顶栏那个按钮上
  （`host-patches/0001-our-ui.patch`），而这正是它替换掉的那件事：那个按钮原先开新标签页跳 `/ai/chat`，
  而那个页面已按 `D52 ①` 停用；换成开抽屉，顺带把那处空指向修好。

  The assistant's **shell**: a drawer sliding in from the right. Everything drawn is `AssistantPanel.vue`;
  this file owns the drawer and its open/closed state and nothing else.

  The shape follows the main repo's (`AiAssistantDrawer.vue`): `direction="rtl"`, `size` of
  `min(600px, 92vw)` so it does not fill a narrow screen, and `v-model` for open/closed. The entry point is
  not here — it is the host's top-bar button (`host-patches/0001-our-ui.patch`), which is exactly what this
  replaces: that button used to open `/ai/chat` in a new tab, and `D52 ①` switched that page off; making it
  open this drawer repairs that dead end as a side effect.
-->
<template>
  <el-drawer
    v-model="open"
    :size="'min(600px, 92vw)'"
    direction="rtl"
    :with-header="false"
    class="assistant-drawer"
  >
    <AssistantPanel />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AssistantPanel from './AssistantPanel.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
</script>

<style scoped>
.assistant-drawer :deep(.el-drawer__body) {
  padding: 12px;
}
</style>
