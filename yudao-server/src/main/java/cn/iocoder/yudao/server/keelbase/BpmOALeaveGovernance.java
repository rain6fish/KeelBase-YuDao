// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.bpm.controller.admin.oa.vo.BpmOALeavePageReqVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.oa.BpmOALeaveDO;
import cn.iocoder.yudao.module.bpm.dal.mysql.oa.BpmOALeaveMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * BPM's governance at the module's own seam, over the leave request it ships — the second upstream
 * module to be held by KeelBase, after CRM. What it is here to answer is not "does CRM's shape repeat"
 * but "what does a module that does <em>not</em> carry the owner the way CRM does cost".
 *
 * <p><b>The owner is an argument, not a column on the VO.</b> CRM's owner lives on the row and on the
 * save VO, so the advice could stamp a field. Here
 * {@code BpmOALeaveServiceImpl.getLeavePage} and {@code createLeave} take the user id as a
 * <b>parameter</b>, and the module's own controller passes whatever the request says. So the advice
 * rewrites the argument — {@code call.proceed(args)} with the caller's id put in slot 0 — rather than a
 * field. Same question, different place to answer it, and that difference is the per-module reading the
 * onboarding checklist says does not compress.
 *
 * <p><b>The owner column is spelled differently.</b> {@code BpmOALeaveDO.userId}, not CRM's
 * {@code ownerUserId}. {@link YudaoModuleScope} takes the owner as a string, so nothing in the machine
 * changes; what changes is which field the module's advice has to name.
 *
 * <p><b>The narrowing here is by the applicant only, and that is a declared narrowing.</b> The module
 * lets an approver act on someone else's leave — that is what the process engine is for. This advice
 * asks only "is this row the caller's", so it is <b>stricter than the module, not equal to it</b>, and
 * widening it to approvals is a decision about this host's vocabulary that is left unmade. The
 * onboarding file owes this sentence for every module; this is BPM's.
 *
 * <p><b>The create path is governed but not exercised.</b> {@code createLeave} stamps the applicant the
 * same way the page is narrowed, because the two take the same argument — but this module's create
 * first calls the process engine, and this host has no process definition deployed, so no gate can run
 * that path. It is written, marked here as unexercised, and recorded as this module's cost rather than
 * looked past.
 *
 * BPM 在其**模块自己的接缝**上的治理，治理的是它自带的请假单——继 CRM 之后第二个被 KeelBase 管住的
 * 上游模块。它来这里要回答的不是「CRM 的形状复现了吗」，而是「一个**归属**长得跟 CRM 不一样的模块，代价是什么」。
 *
 * <p><b>属主是入参，不是 VO 上的列。</b>CRM 的属主既在行上、又在保存 VO 上，故那条通知盖的是**字段**。这里
 * {@code BpmOALeaveServiceImpl.getLeavePage} 与 {@code createLeave} 把用户 id 当**参数**，而模块自己的控制器
 * 传的是请求里写的东西。故本通知改的是**实参**——把调用方的 id 放进第 0 槽、`call.proceed(args)`——而不是字段。
 * 同一个问题、换了个地方回答，而那个差别正是接入清单说的「不肯压缩」的那部分读。
 *
 * <p><b>属主列的拼法不同。</b>是 {@code BpmOALeaveDO.userId}，不是 CRM 的 {@code ownerUserId}。
 * {@link YudaoModuleScope} 把属主当字符串收，故机器里没有任何东西要改；要改的是**这条通知得点名哪个字段**。
 *
 * <p><b>这里的收束只认申请人，而那是一处**声明的收窄**。</b>模块允许审批人处理**别人**的请假——那正是流程引擎
 * 的用途。本通知只问「这一行是不是调用方的」，故它**比模块更严、不是等于**；要放宽到审批，是对**这个宿主词表**的
 * 决定，此处留而未决。接入文件要求每个模块交待这句话；这就是 BPM 的那一句。
 *
 * <p><b>创建那条路被治理了，但没被跑过。</b>{@code createLeave} 盖章申请人的方式与分页收束相同，因为两者取的
 * 是同一个参数——但本模块的创建**先调流程引擎**，而本宿主**没有任何流程定义被部署**，故没有门禁跑得了那条路。
 * 它写下了、在这里标为**未跑过**、并作为**本模块的代价**记下，而不是绕过去不看。
 */
@Aspect
@Component
public class BpmOALeaveGovernance implements GovernedModule {

    private final YudaoModuleScope scope;
    private final BpmOALeaveMapper mapper;

    public BpmOALeaveGovernance(YudaoModuleScope scope, BpmOALeaveMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    /** Declares this module governed, so the set is countable — see {@link GovernedModuleRegistry}. */
    @Override
    public String module() {
        return "bpm";
    }

    @Override
    public String ownerField() {
        return "user_id";
    }

    /**
     * The page, narrowed at the call rather than by whoever calls it. The user id is an argument here,
     * so the caller's id replaces whatever the request carried — a path passing someone else's id
     * cannot widen what the caller sees.
     *
     * 分页，收束在**被调用的地方**、而不是由调用它的人决定。用户 id 在这里是**入参**，故调用方的 id **顶掉**
     * 请求带来的那个——传了别人 id 的路径也无法放宽调用方所见。
     */
    @Around("execution(* cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService.getLeavePage(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        Object[] args = call.getArgs();
        if (caller == null || args.length < 2 || !(args[1] instanceof BpmOALeavePageReqVO)) {
            return call.proceed();
        }
        args[0] = caller;
        return call.proceed(args);
    }

    /**
     * A read by id is a bare {@code selectById}, so another's row is handed back as nothing — which is
     * how a row that is not there answers.
     *
     * 按 id 读是一句裸 {@code selectById}，故别人的行**什么都不交回**——而不存在的那一行正是这么答的。
     */
    @Around("execution(* cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService.getLeave(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof BpmOALeaveDO found
                && !scope.belongsToCaller(String.valueOf(found.getUserId()), caller)) {
            return null;
        }
        return row;
    }

    /**
     * A status change by id has to be the caller's row, or it is refused — and the refusal is what
     * lands in the audit as a denial. The module's own {@code updateLeaveStatus} checks only that the
     * row exists.
     *
     * 按 id 改状态必须是调用方自己的行，否则**拒绝**——而被拒正是落进审计的那一条。模块自己的
     * {@code updateLeaveStatus} 只查那一行在不在。
     */
    @Around("execution(* cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService.updateLeaveStatus(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        Object[] args = call.getArgs();
        if (caller != null && args.length >= 1 && args[0] instanceof Long id) {
            BpmOALeaveDO existing = mapper.selectById(id);
            scope.requireOwned(existing == null ? null : String.valueOf(existing.getUserId()), caller);
        }
        return call.proceed();
    }

    /**
     * The applicant the module's create is stamped with. Same shape as the page narrowing — the user id
     * is argument 0 — but <b>unexercised</b>: this host has no process definition, so the create path
     * stops at the engine before any gate could assert on it.
     *
     * 模块创建时盖的申请人。形状与分页收束相同——用户 id 是第 0 个实参——但**未跑过**：本宿主没有流程定义，
     * 故创建那条路在引擎处就断了，任何门禁都断言不到它。
     */
    @Around("execution(* cn.iocoder.yudao.module.bpm.service.oa.BpmOALeaveService.createLeave(..))")
    public Object stampApplicant(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        Object[] args = call.getArgs();
        if (caller == null || args.length < 2) {
            return call.proceed();
        }
        args[0] = caller;
        return call.proceed(args);
    }
}
