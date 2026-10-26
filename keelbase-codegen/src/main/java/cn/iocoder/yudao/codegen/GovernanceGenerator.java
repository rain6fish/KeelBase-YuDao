// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

/**
 * 由一份规格产出该模块的**治理切面**：四条 {@code @Around} 通知钉在模块自己的 service 上——分页收束、
 * 按 id 读屏蔽别人的行、创建时盖章属主、改/删时要求属主，并声明本模块受治理（{@code GovernedModule}）。
 *
 * <p>pointcut **就是**那份被治理的清单，且点的是模块自己的 service、不是反射查找——治理发生在**模块被调用的
 * 地方**，而不是某一个调用方恰好坐着的地方，故 AI 工具路与模块 REST 路被同一批通知管住。
 *
 * <p>Emits the module's governance aspect: the four {@code @Around} advices that point at the module's own
 * service — narrow a page, withhold another's row on a read by id, stamp the owner on create, require
 * ownership on update/delete — plus the {@code GovernedModule} declaration that makes the governed set
 * countable. The pointcut <em>is</em> the list of what is governed, and it names the module's own service
 * rather than a reflective lookup, so governance sits where the module is called.
 */
final class GovernanceGenerator {

    private GovernanceGenerator() {
    }

    static String build(Spec spec) {
        String module = spec.module();
        String entity = spec.entity();
        String subject = spec.subject();
        String label = spec.label();
        String pkg = "cn.iocoder.yudao.server.keelbase.generated";
        String modulePkg = "cn.iocoder.yudao.module." + module;
        String svc = modulePkg + ".service." + module + "." + entity + "Service";

        return """
// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/%s.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output: it is named after nothing the generator writes, so a
// regeneration of the module's code leaves it alone.
// 由适配器从 specs/%s.json 为芋道宿主生成——不要手改。它是**我们自己的**文件、非芋道生成器的产出：
// 不按生成器会写的东西命名，故模块代码重生成时它不受影响。
package %s;

import %s.controller.admin.%s.vo.%sPageReqVO;
import %s.controller.admin.%s.vo.%sSaveReqVO;
import %s.dal.dataobject.%s.%sDO;
import %s.dal.mysql.%s.%sMapper;
import cn.iocoder.yudao.server.keelbase.GovernedModule;
import cn.iocoder.yudao.server.keelbase.YudaoModuleScope;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * %s's governance at the module's own seam: the pointcut <em>is</em> the list of what is governed
 * (keelbase-docs/KEELBASE-MODULE-ONBOARDING.md §2.1), and it names the module's own service, not a
 * reflective lookup.
 *
 * <p>This is the half the first cut did not port. Without it the narrowing lived inside the AI tool,
 * which governs the tool's own call and nothing else — the module's own REST path stayed ungoverned.
 * With it, both paths are held by the same four advices, because the module is governed where the
 * module is called rather than where one caller happens to sit.
 *
 * <p>One thing is <em>easier</em> here than on RuoYi, and it is worth saying because the reverse is the
 * usual direction: Yudao's page request VO already carries the owner column, and the generated mapper
 * already turns it into an {@code eqIfPresent}. So narrowing a page is one {@code setOwnerUserId} before
 * the call, and the total is narrowed too — no post-filtering, and no page whose total disagrees with
 * its rows.
 *
 * %s 在**模块自己的接缝**上的治理：pointcut **就是**那份被治理的清单
 * （`keelbase-docs/KEELBASE-MODULE-ONBOARDING.zh-CN.md` §2.1），且点的是模块自己的 service、不是反射查找。
 *
 * <p>这是第一版**没有移植**的那一半。没有它时，收束住在 AI 工具里——它只管得住工具自己那一次调用，模块自己的
 * REST 路径**不受治理**。有了它，两条路径被**同四条通知**管住：因为治理发生在**模块被调用的地方**，而不是
 * 某一个调用方恰好坐着的地方。
 *
 * <p>有一件事在这里比 RuoYi **更容易**，值得写下来，因为通常方向是反的：芋道的分页请求 VO **本身就带属主列**，
 * 而生成的 mapper 也已经把它变成 `eqIfPresent`。于是收束一次分页 = 调用前一句 `setOwnerUserId`，而且**总数也一起
 * 收窄了**——没有事后过滤，也没有哪一页的 total 与它的行不一致。
 */
@Aspect
@Component
public class %sGovernance implements GovernedModule {

    private final YudaoModuleScope scope;
    private final %sMapper mapper;

    public %sGovernance(YudaoModuleScope scope, %sMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    @Around("execution(* %s.get%sPage(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null && call.getArgs()[0] instanceof %sPageReqVO query) {
            query.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    // The third way into the same rows: a read by id. It cannot be narrowed — there is one row and it is
    // either the caller's or it is not — so another's row is handed back as nothing, which is exactly how
    // a row that is not there answers.
    // 进入同一批行的**第三条路**：按 id 读。它收束不了——只有一行，要么是调用方的、要么不是——故别人的行
    // **什么都不交回**，而不存在的那一行正是这么答的。
    @Around("execution(* %s.get%s(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof %sDO found
                && !scope.belongsToCaller(found.getOwnerUserId(), caller)) {
            return null;
        }
        return row;
    }

    // The generated save VO carries the owner column and marks it required, so a client could name
    // someone else as owner. Stamping it here is what closes that. / 生成的保存 VO 带属主列、且标为必填，
    // 故客户端可以指认别人为属主。**在这里盖章**正是堵上它的那一下。
    @Around("execution(* %s.create%s(..))")
    public Object stampOwner(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        if (caller != null && call.getArgs()[0] instanceof %sSaveReqVO row) {
            row.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    @Around("execution(* %s.update%s(..))"
            + " || execution(* %s.delete%s(..))"
            + " || execution(* %s.delete%sListByIds(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null) {
            // One advice covers the update and both deletes, so the action is read off the method the
            // pointcut matched rather than fixed: a refusal has to name what was refused.
            //
            // 同一段通知同时管更新与两个删除，故 action 从通知命中的那个方法读出来，而不是写死：拒绝必须点名拒了什么。
            String action = call.getSignature().getName().startsWith("delete") ? "delete" : "update";
            for (Long id : ids(call)) {
                %sDO row = id == null ? null : mapper.selectById(id);
                scope.requireOwned(row == null ? null : row.getOwnerUserId(), caller, "%s", action);
            }
        }
        return call.proceed();
    }

    private List<Long> ids(ProceedingJoinPoint call) {
        Object[] args = call.getArgs();
        if (args.length != 1 || args[0] == null) {
            return List.of();
        }
        if (args[0] instanceof %sSaveReqVO row) {
            return row.getId() == null ? List.of() : List.of(row.getId());
        }
        return scope.asIds(args[0]);
    }

    /**
     * Declares this module governed, so the set this host holds is countable rather than only readable
     * in a pointcut (`YD-13`). The name is the segment the check reads out of the pointcuts above.
     *
     * 声明本模块受治理，使本宿主持有的那个集合**数得出来**，而不是只能在上面的 pointcut 里读（`YD-13`）。
     * 这个名字就是那道检查从上面那些 pointcut 里读出来的那一段。
     */
    @Override
    public String module() {
        return "%s";
    }

    @Override
    public String ownerField() {
        return "owner_user_id";
    }
}
""".formatted(module, module, pkg,
                modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity, modulePkg, module, entity,
                module, module, entity, entity, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, entity,
                svc, entity, svc, entity, svc, entity,
                entity, subject, entity, module);
    }
}
