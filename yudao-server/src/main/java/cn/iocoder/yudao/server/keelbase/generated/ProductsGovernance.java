// SPDX-License-Identifier: Apache-2.0
//
// GENERATED for the Yudao host from specs/products.json — do not edit by hand; re-run the adapter.
// Our own file, not Yudao's generator output: it is named after nothing the generator writes, so a
// regeneration of the module's code leaves it alone.
// 由适配器从 specs/products.json 为芋道宿主生成——不要手改。它是**我们自己的**文件、非芋道生成器的产出：
// 不按生成器会写的东西命名，故模块代码重生成时它不受影响。
package cn.iocoder.yudao.server.keelbase.generated;

import cn.iocoder.yudao.module.products.controller.admin.products.vo.ProductsPageReqVO;
import cn.iocoder.yudao.module.products.controller.admin.products.vo.ProductsSaveReqVO;
import cn.iocoder.yudao.module.products.dal.dataobject.products.ProductsDO;
import cn.iocoder.yudao.module.products.dal.mysql.products.ProductsMapper;
import cn.iocoder.yudao.server.keelbase.YudaoModuleScope;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * products's governance at the module's own seam: the pointcut <em>is</em> the list of what is governed
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
 * products 在**模块自己的接缝**上的治理：pointcut **就是**那份被治理的清单
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
public class ProductsGovernance {

    private final YudaoModuleScope scope;
    private final ProductsMapper mapper;

    public ProductsGovernance(YudaoModuleScope scope, ProductsMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    @Around("execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.getProductsPage(..))")
    public Object narrowToCaller(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null && call.getArgs()[0] instanceof ProductsPageReqVO query) {
            query.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    // The third way into the same rows: a read by id. It cannot be narrowed — there is one row and it is
    // either the caller's or it is not — so another's row is handed back as nothing, which is exactly how
    // a row that is not there answers.
    // 进入同一批行的**第三条路**：按 id 读。它收束不了——只有一行，要么是调用方的、要么不是——故别人的行
    // **什么都不交回**，而不存在的那一行正是这么答的。
    @Around("execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.getProducts(..))")
    public Object withholdOthersRow(ProceedingJoinPoint call) throws Throwable {
        Object row = call.proceed();
        Long caller = scope.callerToEnforce();
        if (caller != null && row instanceof ProductsDO found
                && !scope.belongsToCaller(found.getOwnerUserId(), caller)) {
            return null;
        }
        return row;
    }

    // The generated save VO carries the owner column and marks it required, so a client could name
    // someone else as owner. Stamping it here is what closes that. / 生成的保存 VO 带属主列、且标为必填，
    // 故客户端可以指认别人为属主。**在这里盖章**正是堵上它的那一下。
    @Around("execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.createProducts(..))")
    public Object stampOwner(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.creatingCaller();
        if (caller != null && call.getArgs()[0] instanceof ProductsSaveReqVO row) {
            row.setOwnerUserId(String.valueOf(caller));
        }
        return call.proceed();
    }

    @Around("execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.updateProducts(..))"
            + " || execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.deleteProducts(..))"
            + " || execution(* cn.iocoder.yudao.module.products.service.products.ProductsService.deleteProductsListByIds(..))")
    public Object requireOwnership(ProceedingJoinPoint call) throws Throwable {
        Long caller = scope.callerToEnforce();
        if (caller != null) {
            for (Long id : ids(call)) {
                ProductsDO row = id == null ? null : mapper.selectById(id);
                scope.requireOwned(row == null ? null : row.getOwnerUserId(), caller);
            }
        }
        return call.proceed();
    }

    private List<Long> ids(ProceedingJoinPoint call) {
        Object[] args = call.getArgs();
        if (args.length != 1 || args[0] == null) {
            return List.of();
        }
        if (args[0] instanceof ProductsSaveReqVO row) {
            return row.getId() == null ? List.of() : List.of(row.getId());
        }
        return scope.asIds(args[0]);
    }
}
