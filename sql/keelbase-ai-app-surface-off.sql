-- SPDX-License-Identifier: Apache-2.0
--
-- 芋道自带 AI 模块（`yudao-module-ai`）的**应用面**，关停。
--
-- 这是 `D52 ①` 的落地：该模块的**应用页**（对话 / 绘画 / 音乐 / 写作 / 思维导图 / 工作流 / 知识库）
-- 与它们的**管理页**、以及**聊天角色**与**工具管理**，都不再对任何人露面。**只关菜单，不删代码、不删表**。
--
-- **模型配置那两页**（`ai/model/model` 与 `ai/model/apiKey`）起初是**留着**的——它们是
-- `YudaoAi1ChatModelResolver` 那条解析链的配置单源。**`D52 ②` 把那半回退了**：那个 resolver 已删，ai1
-- 改读自己的 `ai1_provider` / `ai1_model` 行（见 `keelbase-assistant-menus.sql`），于是这两页再没有任何
-- 东西在用。本文件现在**连它们一起关掉**——留着只会让人以为模型是在那里配的。模块的表与代码仍然都在，
-- 关的只是菜单。
--
-- 与 `keelbase-menus-without-pages.sql` 同形：**只改状态、按组件匹配、幂等**（第二次跑命中 0 行），
-- **不删任何行**。`updater` / `update_time` 一并写上，好让它与「谁关的」对得上。
--
-- The built-in AI module's application surface, switched off.
--
-- This lands `D52 ①`: the module's application pages (chat, image, music, write, mind map, workflow,
-- knowledge), their manager pages, and the chat-role and tool pages no longer show to anyone. Menus
-- only — no code and no tables are deleted.
--
-- The two model-configuration pages (`ai/model/model` and `ai/model/apiKey`) were **kept** at first: they
-- were the single configuration source for the `YudaoAi1ChatModelResolver` chain. **`D52 ②` reverted that
-- half** — the resolver is gone and ai1 reads its own `ai1_provider` / `ai1_model` rows (see
-- `keelbase-assistant-menus.sql`) — so nothing uses those two pages any more, and this file now switches
-- them off as well: leaving them up would suggest the model is configured there. The module's tables and
-- code all stay; only menus are switched off.
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

-- 2) 模型配置那两页，以及被它们留在空处的 `AI 大模型` 目录（791）——理由见文件头。
--    `794` 的 component 是 `ai/model/apiKey/index.vue`、`800` 的是 `ai/model/model/index.vue`，都按
--    component 匹配；`791` 是那个目录本身，按 id。
-- 2) The two model-configuration pages, and the `AI 大模型` directory (791) they leave empty — see the
--    header for why. `794`'s component is `ai/model/apiKey/index.vue` and `800`'s is
--    `ai/model/model/index.vue`, so both are matched by component; `791` is the directory itself, by id.
UPDATE system_menu SET status = 1, updater = 'keelbase', update_time = now()
WHERE deleted = 0 AND status <> 1
  AND (component IN ('ai/model/model/index.vue', 'ai/model/apiKey/index.vue') OR id IN (791, 794, 800));
