// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.system.dal.dataobject.notify.NotifyMessageDO;
import cn.iocoder.yudao.module.system.dal.mysql.notify.NotifyMessageMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * The system module's governance at its own seam, over the host's in-app messages
 * ({@code system_notify_message}) — the third module, and the first whose owner is not the shape the
 * other two had.
 *
 * <p><b>The owner is a pair, and the module already reads it.</b> A row belongs to
 * {@code (user_id, user_type)} — an administrator and a member with the same numeric id are different
 * people, so pinning the id alone would leave the other half forgeable. And unlike CRM and BPM, the
 * module's own "my" queries already filter on both: {@code selectPage(vo, userId, userType)} and
 * {@code updateListRead(ids, userId, userType)} both carry {@code eq(user_id)} and {@code eq(user_type)}.
 * So the row-scope question here is answered **by the module**, and what this aspect has to supply is
 * the one thing the module cannot know: whose pair it is. That is a smaller job than CRM's — and the
 * reason is upstream's code, not an abstraction this line built.
 *
 * <p><b>What that costs in observable behaviour.</b> Because the write path scopes itself, a call that
 * names someone else's message is not <em>refused</em> — it matches no row and changes nothing. On CRM
 * and BPM the advice raises a denial, and that denial is what the audit keeps; here there is none to
 * keep. Recorded rather than dressed up: this module's cross-subject attempt is a no-op, and the gate
 * asserts the row is untouched rather than that something was denied.
 *
 * <p><b>Two arities, so two advices.</b> Upstream does not put the pair in the same place twice:
 * {@code getMyMyNotifyMessagePage(vo, userId, userType)} and {@code updateNotifyMessageRead(ids, userId,
 * userType)} carry it at {@code args[1..2]}, while {@code getUnreadNotifyMessageCount(userId, userType)},
 * {@code getUnreadNotifyMessageList(userId, userType, size)} and {@code updateAllNotifyMessageRead(userId,
 * userType)} carry it at {@code args[0..1]}. Pinning it is therefore one advice per arity — a small
 * thing, and exactly the kind of small thing the onboarding checklist says does not compress.
 *
 * <p><b>The one path that is genuinely unscoped is a read by id.</b> {@code getNotifyMessage(Long)} is a
 * bare {@code selectById}, so another subject's message comes back whole. It is answered the way CRM
 * answers its own: another's row is handed back as nothing.
 *
 * <p><b>What is deliberately not touched.</b> {@code createNotifyMessage(userId, userType, …)} takes the
 * same parameter names and means the opposite thing — that {@code userId} is the <b>recipient</b>, not
 * the caller. A blanket "stamp the caller" advice would break every notification this host sends. It is
 * left alone, and named here so the next person does not "fix" it into a bug.
 *
 * 系统模块在其**自己的接缝**上的治理，治理的是宿主的站内信（`system_notify_message`）——第三个模块，也是第一个
 * **属主形状与另外两个不同**的模块。
 *
 * <p><b>属主是**一对**，而且模块自己就读它。</b>一行属于 `(user_id, user_type)`——管理员与同号会员是**两个人**，
 * 故只钉 id 会留下另一半可伪造。而与 CRM、BPM 不同，模块自己的「我的」查询**已经**按两者过滤：
 * `selectPage(vo, userId, userType)` 与 `updateListRead(ids, userId, userType)` 都带 `eq(user_id)` 与
 * `eq(user_type)`。于是这里的**行范围问题是模块自己回答的**，本切面要补的只是模块无从知道的那一件事：**这对是谁的**。
 * 这比 CRM 的活小——而原因是**上游的代码**，不是本线造出来的某个抽象。
 *
 * <p><b>那个代价在可观测行为上是什么。</b>因为写路径自己收束，一次点名了**别人**消息的调用**不是被拒**——它匹配不到
 * 任何行、什么都不改。在 CRM 与 BPM 上，切面会**抛出拒绝**，而那次拒绝正是审计留下的；这里**没有可留的**。记下来、
 * 不粉饰：本模块的跨主体尝试是**静默 no-op**，而门禁断言的是**那一行没动**，不是「有东西被拒了」。
 *
 * <p><b>两种实参形状，故两条通知。</b>上游不把这一对放在同一个位置：`getMyMyNotifyMessagePage(vo, userId,
 * userType)` 与 `updateNotifyMessageRead(ids, userId, userType)` 带在 {@code args[1..2]}，而
 * `getUnreadNotifyMessageCount(userId, userType)`、`getUnreadNotifyMessageList(userId, userType, size)` 与
 * `updateAllNotifyMessageRead(userId, userType)` 带在 {@code args[0..1]}。故钉它**每种形状一条通知**——小事，而正是
 * 接入清单说「不肯压缩」的那种小事。
 *
 * <p><b>唯一真正不收束的那条路是按 id 读。</b>{@code getNotifyMessage(Long)} 是一句裸 {@code selectById}，故**另一个
 * 主体的信整封回来**。它按 CRM 回答自己那问的方式回答：别人的行**什么都不交回**。
 *
 * <p><b>刻意不碰的。</b>{@code createNotifyMessage(userId, userType, …)} 取的是**同样两个参数名**、意思却相反——
 * 那个 {@code userId} 是**收件人**、不是调用方。一刀切的「盖上调用方」会打坏本宿主发出的每一封通知。它被留在原样，并
 * 在这里点名，好让下一个人**别把它「修」成一个 bug**。
 */
@Aspect
@Component
public class SystemNotifyMessageGovernance implements GovernedModule {

    /** The caller is an administrator: this host's identity bridge resolves Yudao access tokens. */
    private static final Integer CALLER_USER_TYPE = UserTypeEnum.ADMIN.getValue();

    private final YudaoModuleScope scope;
    private final NotifyMessageMapper mapper;

    public SystemNotifyMessageGovernance(YudaoModuleScope scope, NotifyMessageMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    /** Declares this module governed, so the set is countable — see {@link GovernedModuleRegistry}. */
    @Override
    public String module() {
        return "system";
    }

    /**
     * The owner is a pair here, not a column — said plainly, because {@link GovernedModule#ownerField()}
     * assumes one field and this module is the first to show that assumption is not general.
     *
     * 这里的属主是**一对**、不是一列——说白，因为 {@link GovernedModule#ownerField()} 假定**一个**字段，而本模块是
     * 第一个显示那个假定**不成立**的。
     */
    @Override
    public String ownerField() {
        return "user_id + user_type";
    }

    /**
     * The pair at {@code args[1..2]}: the "my" page and the read-marking write. Both already scope on
     * it, so what the caller's pair buys is the answer to <em>whose</em> it is.
     *
     * {@code args[1..2]} 上的那一对：「我的」分页与标记已读的写。两者本就按它收束，故盖上调用方那一对买到的是
     * **它是谁的**那个答案。
     */
    @Around("execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.getMyMyNotifyMessagePage(..))"
            + " || execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.updateNotifyMessageRead(..))")
    public Object pinSubject(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        Object[] args = call.getArgs();
        if (caller == null || args.length < 3
                || !(args[1] instanceof Long) || !(args[2] instanceof Integer)) {
            return call.proceed();
        }
        args[1] = caller;
        args[2] = CALLER_USER_TYPE;
        return call.proceed(args);
    }

    /**
     * The same pair at {@code args[0..1]}: the unread count, the unread list, and the "mark everything
     * read" write. A second advice rather than a shared one, because upstream put the pair in a
     * different place — read by the gate only on the two above, and written down here as unexercised
     * rather than left as a hole.
     *
     * 同一对在 {@code args[0..1]} 上：未读计数、未读列表、以及「全部标记已读」那个写。**另立一条**通知、而不共用，
     * 因为上游把这一对放在**另一个位置**——门禁只跑了上面那两条，而这一条在此记为**未跑过**，而不是留成一个洞。
     */
    @Around("execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.getUnreadNotifyMessageList(..))"
            + " || execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.getUnreadNotifyMessageCount(..))"
            + " || execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.updateAllNotifyMessageRead(..))")
    public Object pinSubjectAtFront(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        Object[] args = call.getArgs();
        if (caller == null || args.length < 2
                || !(args[0] instanceof Long) || !(args[1] instanceof Integer)) {
            return call.proceed();
        }
        args[0] = caller;
        args[1] = CALLER_USER_TYPE;
        return call.proceed(args);
    }

    /**
     * The one genuinely unscoped path: a read by id. Another subject's message is handed back as
     * nothing — the pair is checked, not just the id, because the id alone is shared between an
     * administrator and a member.
     *
     * 唯一真正不收束的那条路：按 id 读。**另一个主体**的信什么都不交回——查的是**那一对**、不只是 id，因为 id 本身
     * 在管理员与会员之间是**共用的**。
     */
    @Around("execution(* cn.iocoder.yudao.module.system.service.notify.NotifyMessageService.getNotifyMessage(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof NotifyMessageDO found
                && (!scope.belongsToCaller(String.valueOf(found.getUserId()), caller)
                        || !CALLER_USER_TYPE.equals(found.getUserType()))) {
            return null;
        }
        return row;
    }
}
