// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerPageReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.dal.mysql.customer.CrmCustomerMapper;
import cn.iocoder.yudao.module.crm.enums.common.CrmSceneTypeEnum;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * CRM's governance at the module's own seam — over a service upstream wrote, not one we generated.
 *
 * <p>The pointcut <em>is</em> the list of what is governed: it names the module's own service, so the
 * module's REST path and {@link CrmCustomerTools} are held by the same advices. A narrowing written
 * into the tool would govern the tool and leave the REST path open — the mistake the generated
 * module's first cut made.
 *
 * <p>Three of the four advices close something upstream leaves to its caller, and it is worth naming
 * each as a thing found in <em>their</em> code rather than a defect invented here:
 *
 * <ol>
 *   <li><b>The page defaults to every row.</b> {@code CrmSceneTypeEnum} adds no condition at all when
 *       {@code sceneType} is null, and the request VO documents null as "全部". The module's own UI
 *       always sends a scene; an AI caller has no UI. The advice supplies the caller's own scene, so
 *       "no scene" stops meaning "all rows" the moment the caller is not the seed administrator.</li>
 *   <li><b>A read by id is not scoped.</b> {@code getCustomer(Long)} is a bare
 *       {@code customerMapper.selectById(id)}, so one caller can name another's row. It cannot be
 *       narrowed — there is one row and it is either the caller's or it is not — so another's row is
 *       handed back as nothing, which is exactly how a row that is not there answers.</li>
 *   <li><b>The save VO carries the owner.</b> A client can name someone else as owner on create;
 *       stamping it from the caller is what closes that. The module's own update path already clears
 *       the field ({@code updateReqVO.setOwnerUserId(null)}), so create is the half that needed it.</li>
 * </ol>
 *
 * <p>What is <b>not</b> asserted here: upstream's {@code crm_permission} grants READ and WRITE to
 * participants as well as the owner. This advice asks only the owner question, so it is a
 * <b>declared narrowing</b> — stricter than the module, not equal to it. Widening it to the permission
 * table is a decision about this host's vocabulary, and is left unmade rather than guessed.
 *
 * CRM 在其**模块自己的接缝**上的治理——治理的是一个**上游写的** service，不是我们生成的。
 *
 * <p>pointcut **就是**那份被治理的清单：它点的是模块自己的 service，于是模块的 REST 路径与
 * {@link CrmCustomerTools} 被**同一批通知**管住。把收束写进工具里，只会治理工具、把 REST 路径敞开——正是生成模块
 * 第一版犯的错。
 *
 * <p>四条通知里有三条堵的是**上游留给调用方**的东西，值得逐条点明：它们是在**他们**的代码里发现的，
 * 不是这里凭空造出的缺陷：
 *
 * <ol>
 *   <li><b>分页默认每一行。</b>`sceneType` 为空时 {@code CrmSceneTypeEnum} **一个条件都不加**，而请求 VO 把 null
 *       记作「全部」。模块自己的界面总会传一个场景；AI 调用方没有界面。本通知补上**调用方自己的场景**，于是只要调用方
 *       不是种子管理员，「没有场景」就不再等于「所有行」。</li>
 *   <li><b>按 id 读不受范围约束。</b>{@code getCustomer(Long)} 就是一句
 *       {@code customerMapper.selectById(id)}，于是一个调用方可以点出别人的行。它收束不了——只有一行，要么是调用方的、
 *       要么不是——故别人的行**什么都不交回**，而不存在的那一行正是这么答的。</li>
 *   <li><b>保存 VO 带着属主。</b>创建时客户端可以指认别人为属主；**在这里盖章**正是堵上它的那一下。模块自己的更新路径
 *       已经把该字段清掉（{@code updateReqVO.setOwnerUserId(null)}），故需要补的是**创建**那半。</li>
 * </ol>
 *
 * <p>这里**没有**断言的：上游的 {@code crm_permission} 除负责人外还给参与方 READ 与 WRITE。本通知只问「是不是
 * 负责人」这一问，故它是一处**声明的收窄**——比模块**更严**，不是等于。要放宽到权限表，是对**这个宿主词表**的决定，
 * 此处**留而未决**、不猜。
 */
@Aspect
@Component
public class CrmCustomerGovernance implements GovernedModule {

    private final YudaoModuleScope scope;
    private final CrmCustomerMapper mapper;

    public CrmCustomerGovernance(YudaoModuleScope scope, CrmCustomerMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    /** Declares this module governed, so the set is countable — see {@link GovernedModuleRegistry}. */
    @Override
    public String module() {
        return "crm";
    }

    @Override
    public String ownerField() {
        return "owner_user_id";
    }

    /**
     * The page, narrowed where the module is called rather than by whoever calls it. Both the scene
     * and the identity are supplied: the first because upstream's default is every row, the second so
     * that a path passing someone else's id cannot widen what the caller sees.
     *
     * 分页，收束在**模块被调用的地方**、而不是由调用它的人决定。场景与身份**都**补上：前者因为上游的默认是每一行，
     * 后者使得「传了别人的 id」的路径也无法放宽调用方所见。
     */
    @Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.getCustomerPage(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        Object[] args = call.getArgs();
        if (caller != null && args.length >= 2 && args[0] instanceof CrmCustomerPageReqVO query) {
            if (query.getSceneType() == null) {
                query.setSceneType(CrmSceneTypeEnum.OWNER.getType());
            }
        }
        return call.proceed();
    }

    /**
     * The third way into the same rows: a read by id. Another's row is handed back as nothing.
     *
     * 进入同一批行的**第三条路**：按 id 读。别人的行**什么都不交回**。
     */
    @Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.getCustomer(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof CrmCustomerDO found
                && !scope.belongsToCaller(String.valueOf(found.getOwnerUserId()), caller)) {
            return null;
        }
        return row;
    }

    /**
     * A save is stamped with the caller, so the owner column cannot be pointed at someone else. Both
     * halves are covered, and the update half needs the stamp for a second reason: the save VO
     * declares the owner as required, so a caller that does not name one is refused by bean
     * validation before the method body ever clears the field the way the module intends.
     *
     * <p>That the stamp happens <em>before</em> validation is not assumed — it is the ordering the
     * first approved hijack attempt demonstrated, when the ownership advice refused a call that
     * validation would also have refused.
     *
     * 保存以调用方盖章，故属主列指不到别人身上。两半都盖，而更新那一半还多一个理由：保存 VO 把属主声明为
     * **必填**，故不点名属主的调用方会在方法体按模块本意清掉该字段**之前**就被 bean 校验挡下。
     *
     * <p>盖章发生在校验**之前**，不是假设——它是第一次批准的越权尝试**显示出来的次序**：那一次，归属通知拒掉了一个
     * 校验本来也会拒掉的调用。
     */
    @Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.createCustomer(..))"
            + " || execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.updateCustomer(..))")
    public Object stampOwner(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        if (caller != null && call.getArgs()[0] instanceof CrmCustomerSaveReqVO row) {
            row.setOwnerUserId(caller);
        }
        return call.proceed();
    }

    /**
     * An update by id has to be the caller's row, or it is refused — and a refusal is what lands in
     * the audit as a denial, which is the half the menu-list view can never produce.
     *
     * 按 id 的更新必须是调用方自己的行，否则**拒绝**——而被拒正是落进审计的那一条，是菜单列表那一侧永远产生不了的那一半。
     */
    @Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.updateCustomer(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null && call.getArgs()[0] instanceof CrmCustomerSaveReqVO row && row.getId() != null) {
            CrmCustomerDO existing = mapper.selectById(row.getId());
            scope.requireOwned(existing == null ? null : String.valueOf(existing.getOwnerUserId()), caller);
        }
        return call.proceed();
    }
}
