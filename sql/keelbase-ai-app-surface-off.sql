-- SPDX-License-Identifier: Apache-2.0
--
-- 芋道自带 AI 模块（`yudao-module-ai`）的**应用面**，关停。
--
-- 这是 `D52 ①` 的落地：该模块的**应用页**（对话 / 绘画 / 音乐 / 写作 / 思维导图 / 工作流 / 知识库）
-- 与它们的**管理页**、以及**聊天角色**与**工具管理**，都不再对任何人露面。**只关菜单，不删代码、不删表**
-- —— 该模块仍要在，因为 `YudaoAi1ChatModelResolver` 用它的 `AiModelService` 给 ai1 的 agent 解析模型。
--
-- **保留两条**：`ai/model/model`（模型配置）与 `ai/model/apiKey`（API 密钥）—— 它们是上面那条解析链的配置
-- 单源，关掉就没有「只配一次模型」这回事了。
--
-- 与 `keelbase-menus-without-pages.sql` 同形：**只改状态、按组件匹配、幂等**（第二次跑命中 0 行），
-- **不删任何行**。`updater` / `update_time` 一并写上，好让它与「谁关的」对得上。
--
-- The built-in AI module's application surface, switched off.
--
-- This lands `D52 ①`: the module's application pages (chat, image, music, write, mind map, workflow,
-- knowledge), their manager pages, and the chat-role and tool pages no longer show to anyone. Menus
-- only — no code and no tables are deleted, because the module stays: `YudaoAi1ChatModelResolver`
-- resolves ai1's model through its `AiModelService`.
--
-- Two rows are kept: `ai/model/model` and `ai/model/apiKey`, the configuration source for that
-- resolution — without them there is no single place a model is configured.
--
-- Same shape as `keelbase-menus-without-pages.sql`: status only, matched by component, idempotent
-- (a second run matches zero rows), and no row is ever deleted.

UPDATE system_menu SET status = 1, updater = 'keelbase', update_time = now()
WHERE deleted = 0 AND status <> 1 AND component IN (
  'ai/chat/index/index.vue',
  'ai/chat/manager/index.vue',
  'ai/image/index/index.vue',
  'ai/image/manager/index.vue',
  'ai/knowledge/knowledge/index',
  'ai/mindmap/index/index.vue',
  'ai/mindmap/manager/index',
  'ai/model/chatRole/index.vue',
  'ai/model/tool/index.vue',
  'ai/music/index/index.vue',
  'ai/music/manager/index.vue',
  'ai/workflow/index.vue',
  'ai/write/index/index.vue',
  'ai/write/manager/index.vue'
);
