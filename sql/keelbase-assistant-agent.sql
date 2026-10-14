-- SPDX-License-Identifier: Apache-2.0
--
-- 控制台助手的**常驻 agent**：它的模型链、它的系统提示词。
--
-- **为什么它是一条种子、而不是一句手改。** 在那之前，「这台部署的助手是谁」只活在某个人的手敲里 ——
-- 建 provider / model / agent 三步各敲一次，提示词是临时的调试文案（`你是一个只会照做的助手…`）。那正是
-- `YD-30 ③` 记下的毛病：助手把一百个客户 id 全倒出来，而没人替它写过「回答该怎么给」。⇒ 提示词与那条链
-- 一起进仓，**改了可评审、部署可复现**。
--
-- **key 不进仓**，而且**不需要新增任何配置**。provider 的 `api_key` 写的是
-- `${DEEPSEEK_API_KEY:${spring.ai.openai.api-key}}` —— 一个**嵌套默认值**：部署方给得起 `DEEPSEEK_API_KEY`
-- 就用它的，给不起就落到**这个部署已经在传的那个模型 key**（`--spring.ai.openai.api-key`，本部署的 compose 与
-- `run-server.sh` 都在传）。`ai1` 在**组装调用参数时**用 Spring 的 `Environment` 解析这类占位符
-- （`Ai1ModelServiceImpl` 的 `resolveSpringPlaceholders`，库里存的原文不动），故两种写法都解得开；解析不开时它会**响亮地抛**
-- （`CONFIG_PLACEHOLDER_NOT_RESOLVED`），不会静默拿一个空 key 去请求。
--
-- **模型名是部署方的选择**：这里填的是本部署在用的那个。换模型只改这一行。
--
-- `status = 0` 是**启用**（上游语义）。这里三条都固定用 `9001` —— 本线自己的 id 段，与门禁现建现拆的那些
-- （几十号）离得远。
--
-- **幂等，而且是 upsert、不是只插**：每条按自己的 id 冲突时**更新**。这与本仓别的种子（`where not exists`、只插、
-- 永不刷新陈旧行）**不一样，是刻意的**：这个文件的主体就是那段提示词，而一个改不动的提示词正是 `YD-30 ③` 要治的毛病
-- —— 改了仓里的字、部署那边却还是旧的，**静默地**没生效。重跑不改内容，只刷新 `update_time`。
--
-- The console assistant's **standing agent**: its model chain and its system prompt.
--
-- **Why this is a seed rather than a value somebody typed.** Until it existed, "which assistant does this
-- deployment have" lived in someone's hands — a provider, a model and an agent created one call at a time,
-- with a prompt borrowed from a gate probe. That is exactly the defect `YD-30 ③` records: the assistant
-- dumped a hundred customer ids, because nobody had told it how an answer should read. So the prompt and
-- the chain enter the repository together — reviewable when changed, reproducible when deployed.
--
-- **No key in the repository — and no new configuration either.** The provider's `api_key` says
-- `${DEEPSEEK_API_KEY:${spring.ai.openai.api-key}}`, a **nested default**: a deployment that provides
-- `DEEPSEEK_API_KEY` uses it, and one that does not falls back to **the model key it already passes**
-- (`--spring.ai.openai.api-key`, which both the compose file and `run-server.sh` pass). `ai1` resolves such
-- placeholders with Spring's `Environment` when it assembles the call parameters, leaving the stored text
-- alone; an unresolvable one is refused loudly (`CONFIG_PLACEHOLDER_NOT_RESOLVED`) rather than silently
-- becoming an empty key.
--
-- **The model name is the deployment's choice**, and this is the one this deployment runs. Changing it is
-- this one line.
--
-- `status = 0` means enabled (upstream's convention). All three rows use the fixed id `9001` — this line's
-- own range, far from the small ids the gates create and tear down.
--
-- **Idempotent, and an upsert rather than an insert-only**: each row updates itself on the id it owns. That
-- differs from this repository's other seeds (`where not exists`, insert-only, never refreshing a stale row)
-- **on purpose**: the prompt is what this file is about, and a prompt that cannot be corrected is precisely the
-- defect `YD-30 ③` names — editing the repository would change nothing on a deployment, silently. Re-running
-- changes no content; it refreshes `update_time`.

INSERT INTO ai1_provider (id, name, base_url, api_key, headers, status, remark,
                          creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 9001, '控制台助手 · DeepSeek', 'https://api.deepseek.com', '${DEEPSEEK_API_KEY:${spring.ai.openai.api-key}}', NULL, 0,
       '控制台右侧抽屉用的常驻助手；key 在请求时由占位符解析',
       'keelbase', now(), 'keelbase', now(), 0, 1
ON CONFLICT (id) DO UPDATE
   SET name = EXCLUDED.name, base_url = EXCLUDED.base_url, api_key = EXCLUDED.api_key,
       remark = EXCLUDED.remark, status = EXCLUDED.status, update_time = now(), updater = 'keelbase';

INSERT INTO ai1_model (id, provider_id, name, model, type, status,
                       creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 9001, 9001, '控制台助手 · chat', 'deepseek-v4-flash', 0, 0,
       'keelbase', now(), 'keelbase', now(), 0, 1
ON CONFLICT (id) DO UPDATE
   SET provider_id = EXCLUDED.provider_id, name = EXCLUDED.name, model = EXCLUDED.model,
       type = EXCLUDED.type, status = EXCLUDED.status, update_time = now(), updater = 'keelbase';

-- 提示词是**这一段**，而它是本文件的主体：它在说三件事 —— 回答要短、数据只有调用方自己那份、写不会立刻生效。
-- 最后一条尤其要紧：平台的引擎**真的**会把写扣下等人，而把「已提交」说成「已完成」是助手最容易说出的假话。
--
-- The prompt is **this block**, and it is what this file is really about. It says three things: keep answers
-- short; the data is the caller's own; and a write does not take effect immediately. The last one matters
-- most — the engine really does hold writes for a human, and calling "submitted" "done" is the easiest lie
-- for an assistant to tell.
INSERT INTO ai1_agent (id, name, introduction, provider_id, model_id, system_prompt,
                       knowledge_base_ids, mcp_ids, skill_ids, status,
                       creator, create_time, updater, update_time, deleted, tenant_id)
SELECT 9001, '控制台助手',
       '控制台右侧抽屉里的助手：能读本部署的业务数据、能发起需要人工确认的写',
       9001, 9001,
       '你是这个控制台（KeelBase）里的助手，替**当前登录的这个人**办事。'
       || E'\n\n回答怎么给：\n'
       || '- **默认两三句**：先给结论或数字，再补一条必要的说明。\n'
       || '- **不要把原始数据倒出来**：不列编号、不贴整张表、不逐行复述；要举例子最多三五条，并写明「例如」。\n'
       || '- 不确定就说「不确定」；需要澄清就问一句，不要猜一个答案把话填满。\n\n'
       || '怎么用工具：\n'
       || '- 需要真实数据时就用工具去取，**不要凭记忆编**。\n'
       || '- 你能看到的数据**只有当前调用方自己那一份**（行范围由平台决定），所以别说「全部 / 所有人」，除非工具确实这么答。\n'
       || '- **写操作不会立刻生效**：它会被平台扣下、等一个人在控制台里确认。如实说「已提交，等确认」，'
       || '**不要**把「已提交」说成「已完成」。',
       NULL, NULL, NULL, 0,
       'keelbase', now(), 'keelbase', now(), 0, 1
ON CONFLICT (id) DO UPDATE
   SET name = EXCLUDED.name, introduction = EXCLUDED.introduction,
       provider_id = EXCLUDED.provider_id, model_id = EXCLUDED.model_id,
       system_prompt = EXCLUDED.system_prompt, status = EXCLUDED.status,
       update_time = now(), updater = 'keelbase';
