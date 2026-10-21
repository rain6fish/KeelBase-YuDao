// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.module.ai1.harness.session;

/**
 * A deployment's own copy of a session's turns, told as they happen.
 *
 * <p><b>Why this seam exists.</b> The assistant keeps its sessions and messages in this module's
 * tables. A deployment that also keeps its own conversation transcript — YuDao's decision-trace page
 * reads the runtime's {@code conversation_messages} — has no way to learn that a turn happened: the
 * module writes only its own rows. Without this seam the trace page lists nothing, because nothing in
 * its store was ever written by the assistant, even though the audit and side-effect rows those turns
 * produced are stamped with this session's id. The seam closes that gap without teaching the module
 * about the runtime: the module reports a turn, the deployment decides where (and whether) to record it.
 *
 * <p><b>Optional, and it stays optional.</b> No implementation means this module behaves exactly as it
 * did on its own; the listener list is simply empty. Every call is made after the module's own row has
 * been written, and a listener failure is logged rather than allowed to fail the user's turn — a copy
 * kept by the deployment is not worth breaking the assistant for.
 *
 * <p><b>A file of KeelBase's inside an upstream module</b> — registered in
 * {@code keelbase-docs/KEELBASE-UPSTREAM.md}.
 *
 * <p>部署方自己的会话副本，随回合发生而被通知。
 *
 * <p><b>这条缝为什么存在。</b>助手的会话与消息存在本模块自己的表里。一个**另外**还留着自己的会话记录的
 * 部署——芋道的决策轨迹页读的是运行时的 `conversation_messages`——无从得知发生过一个回合：本模块只写自己的
 * 行。没有这条缝，轨迹页什么也列不出，因为它的存储里**从来没有**助手写下的行，尽管那些回合产出的审计行与
 * 副作用行都带着本会话的 id。这条缝补上这个缺口，而不必让本模块认识运行时：模块报告一个回合，部署方决定
 * 记到哪里（以及记不记）。
 *
 * <p><b>可选，而且一直可选。</b>没有实现时，本模块的行为与它单独使用时**完全一样**——监听器列表就是空的。
 * 每次调用都发生在模块自己的行写完之后，监听器的失败只记日志、**不许**让用户这一轮失败：部署方留的副本，
 * 不值得拿助手去换。
 *
 * <p><b>上游模块里的一份 KeelBase 文件</b> —— 登记在 {@code keelbase-docs/KEELBASE-UPSTREAM.md}。
 */
public interface Ai1TranscriptListener {

    /**
     * A caller's message, after it has been stored by this module.
     *
     * 调用方的一句消息，在本模块把它落库之后。
     */
    void userTurn(Long sessionId, Long userId, String content);

    /**
     * The assistant's reply, after the placeholder row has been filled in.
     *
     * 助手的回复，在占位行被回填之后。
     */
    void assistantTurn(Long sessionId, Long userId, String content);
}
