// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationStore;
import cn.com.keelbase.runtime.identity.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link YudaoAssistantTranscript} 的测试：把每个回合按**会话 id**追加进运行时会话记录，落在**自己的
 * 事务**里，且失败只记日志、不上抛。
 *
 * <p>断言的是本类 Javadoc 写下的四条承诺：回合挂在 {@code String.valueOf(sessionId)} 下、属主是说话的人
 * （{@code userId} 加用户角色）、角色与内容原样送出；缺会话、缺属主或缺文本的回合**跳过**；追加跑在
 * {@code REQUIRES_NEW} 事务里 —— 否则被捕获的失败仍会把 ai1 的消息事务标成只回滚；追加失败**不**让这一轮
 * 失败。
 *
 * <p>Tests for {@link YudaoAssistantTranscript}: each turn is appended to the runtime store under the
 * <b>session id</b>, in its <b>own</b> transaction, and a failed append is logged rather than raised.
 * They assert the four promises in the class Javadoc: a turn lands under {@code String.valueOf(sessionId)}
 * with the speaker as owner ({@code userId} plus the user role) and role/content verbatim; a turn missing
 * a session, an owner or text is skipped; the append runs with {@code REQUIRES_NEW} — otherwise a caught
 * failure would still mark ai1's message transaction rollback-only; and a failed append does not fail the
 * turn that produced it.
 */
class YudaoAssistantTranscriptTest {

    private ConversationStore conversations;
    private PlatformTransactionManager transactions;
    private YudaoAssistantTranscript transcript;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationStore.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        transcript = new YudaoAssistantTranscript(conversations, transactions);
    }

    @Test
    void aUserTurnIsAppendedUnderTheSessionIdWithTheSpeakerAsOwner() {
        transcript.userTurn(7L, 42L, "hello");

        verify(conversations).append("7", new Principal("42", Principal.ROLE_USER),
                ConversationMessage.USER, "hello");
    }

    @Test
    void anAssistantTurnIsAppendedWithTheAssistantRole() {
        transcript.assistantTurn(7L, 42L, "hi there");

        verify(conversations).append("7", new Principal("42", Principal.ROLE_USER),
                ConversationMessage.ASSISTANT, "hi there");
    }

    @Test
    void theAppendRunsInItsOwnTransaction() {
        transcript.userTurn(7L, 42L, "hello");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        // A caught failure in the ai1 message transaction would otherwise mark it rollback-only.
        // 在 ai1 的消息事务里，被捕获的失败否则会把它标成只回滚。
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
    }

    @Test
    void aTurnWithoutASessionOwnerOrTextIsSkipped() {
        transcript.userTurn(null, 42L, "hello");
        transcript.userTurn(7L, null, "hello");
        transcript.userTurn(7L, 42L, null);
        transcript.userTurn(7L, 42L, "   ");
        transcript.assistantTurn(7L, 42L, "");

        verifyNoInteractions(conversations);
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void aFailedAppendIsReportedNotRaised() {
        when(conversations.append(anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("store offline"));

        // The assistant's own row is already written; a missing copy must not fail the turn.
        // 助手自己的行已经写了；副本缺失不许让这一轮失败。
        assertDoesNotThrow(() -> transcript.assistantTurn(7L, 42L, "hi there"));
        verify(conversations).append(eq("7"), any(), eq(ConversationMessage.ASSISTANT), eq("hi there"));
    }
}
