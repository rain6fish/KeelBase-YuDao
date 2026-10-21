// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationStore;
import cn.com.keelbase.runtime.identity.Principal;
import cn.iocoder.yudao.module.ai1.harness.session.Ai1TranscriptListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the console assistant's turns into the runtime's transcript, under the assistant session's
 * id — so the decision-trace page can show the conversation those turns belong to.
 *
 * <p><b>What was broken.</b> The assistant keeps its sessions in the ai1 module, while the trace page
 * reads the runtime's {@code /ai/conversations}, whose rows only the runtime's own chat endpoints
 * write. A turn taken through the assistant therefore left no conversation behind: the page was
 * reachable and listed nothing, even though the audit and side-effect rows produced inside that
 * session were stamped with its id and would have merged into a trace had a conversation existed.
 * Neither identity filtering nor the deployment's user scope was involved — the store was simply
 * never written.
 *
 * <p><b>What this does.</b> It listens to {@link Ai1TranscriptListener} and appends each turn to
 * {@link ConversationStore} under {@code String.valueOf(sessionId)} — the same value
 * {@code YudaoGovernedAgentTools} stamps on the rows the engine writes. One id, one conversation: the
 * list, the ownership check and the merge of turns with audit and effects all follow from the
 * runtime's existing code.
 *
 * <p><b>The caller is the session's owner, not whoever is reading now.</b> The id comes from the
 * session row / the task that carried it, so the transcript belongs to the person who spoke. The
 * runtime's trace endpoint refuses another user's conversation, and the list only answers the
 * caller's own — the ordinary user's scope is unchanged.
 *
 * <p><b>Best effort, and said so.</b> A failure to append is logged and swallowed: the assistant's
 * own row is already written, and a missing trace must not fail the turn that produced it. The
 * append runs in its **own** transaction for that reason: the ai1 module calls this listener from
 * inside its message transaction, and a failed write there would otherwise mark that transaction
 * rollback-only even though the exception was caught — measured: the user's message rolled back and
 * the assistant answered "submission failed". A separate transaction makes the copy independent.
 *
 * 把控制台助手的回合写进运行时的会话记录，挂在**助手会话的 id** 下——于是决策轨迹页能显示这些回合所属的
 * 那条会话。追加**在自己的事务里**：ai1 模块是在它自己的消息事务中调用本监听器的，否则失败的那次写尽管被捕获，
 * 仍会把那个事务标成只回滚——实测：用户消息被回滚、助手答「会话提交失败」。独立事务让副本互不相干。
 *
 * <p><b>原先坏在哪。</b>助手把会话存在 ai1 模块里，而轨迹页读的是运行时的 `/ai/conversations`，那里的行只有
 * 运行时自己的聊天端点会写。于是经助手走的一轮**没有留下会话**：页面到得了、却什么也列不出——尽管那一轮里
 * 产生的审计行与副作用行都盖着它的 id，只要有会话，它们本来就会被并进同一条轨迹。这里既不是身份过滤、也不是
 * 部署的用户范围在起作用：**那个存储从来就没人写过**。
 *
 * <p><b>本类做什么。</b>它监听 {@link Ai1TranscriptListener}，把每个回合按
 * `String.valueOf(sessionId)` 追加进 {@link ConversationStore}——与 `YudaoGovernedAgentTools` 盖在引擎
 * 写的那些行上的**同一个值**。一个 id、一条会话：列表、属主检查、以及回合与审计/副作用的合并，全都由运行时
 * 既有的代码成立。
 *
 * <p><b>调用方是会话的属主，不是此刻的读者。</b>id 取自会话行 / 携带它的那个任务，故记录属于**说话的人**。
 * 运行时的轨迹端点拒绝别人的会话，列表也只答调用方自己的——普通用户的范围没有变。
 *
 * <p><b>尽力而为，并写明。</b>追加失败只记日志、不向上抛：助手自己的行已经写了，缺一条轨迹不许让产出它的那一轮失败。
 */
@Component
@Slf4j
public class YudaoAssistantTranscript implements Ai1TranscriptListener {

    private final ConversationStore conversations;
    private final TransactionTemplate requiresNew;

    public YudaoAssistantTranscript(ConversationStore conversations, PlatformTransactionManager transactions) {
        this.conversations = conversations;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void userTurn(Long sessionId, Long userId, String content) {
        record(sessionId, userId, ConversationMessage.USER, content);
    }

    @Override
    public void assistantTurn(Long sessionId, Long userId, String content) {
        record(sessionId, userId, ConversationMessage.ASSISTANT, content);
    }

    /**
     * Appends one turn under the session's id. A turn without a session, an owner or text is skipped:
     * the runtime's transcript holds turns, and none of those three makes a turn.
     *
     * 按会话 id 追加一个回合。没有会话、没有属主或没有文本的回合跳过：运行时的记录装的是回合，这三者缺一
     * 都不成一个回合。
     */
    private void record(Long sessionId, Long userId, String role, String content) {
        if (sessionId == null || userId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> conversations.append(String.valueOf(sessionId),
                    new Principal(String.valueOf(userId), Principal.ROLE_USER), role, content));
        } catch (RuntimeException failed) {
            // The assistant's own row is written already; a missing copy is reported, not raised.
            // 助手自己的行已经写了；副本缺失只报告、不抛出。
            log.warn("[record][会话({}) 回合({}) 写入运行时会话记录失败]", sessionId, role, failed);
        }
    }
}
