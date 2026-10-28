// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.datapermission.core.util.DataPermissionUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把审计行上的 `userId` 翻成**人名或账号**的那一条窄读面 —— 审计读者也能读，只回三样。
 *
 * <p><b>为什么不在页面里直接问芋道的名录。</b> 审计行只有 `userId`（运行时 `AuditService.row()` 不送
 * 用户名），而 `D49 ②` 定的是「谁」由**宿主自己的名录**补上。可 `/admin-api/system/user/list` 要
 * `system:user:query`，且 `system_users` 又受芋道**按表名的部门数据权限**收窄 —— 实测一个只挂
 * `audit_reader` 的账号拿到的 `simple-list` **只有它自己**。故「给它一条用户查询权限」既开得比要的宽
 * （整张用户管理读面、连联系方式一起），又仍然答不出别人的名字。这一条读面只做那件事。
 *
 * <p><b>授权与审计链同一道门。</b> 管理员，或持有启用 `audit_reader` 角色的非管理员
 * （{@link YudaoAuditReaderRules}，那份角色表是唯一权威）。其余一律 403。没有第三人档，也不看请求头。
 *
 * <p><b>只回三样：编号、账号、昵称。</b> 不收邮箱、手机、部门、角色 —— 「谁」这一问要的是**能叫出名字**，
 * 不是把这个人的档案端出来。数据权限在这里**刻意**用 `executeIgnore` 关掉：审计读者要命名的恰恰是**别人**的
 * 调用行，而部门范围会把答案收窄成他自己；这一读面的范围由**授权**决定（能读审计行的人，才配读这些行的
 * 调用方是谁），不由行范围决定。
 *
 * <p><b>调用方按宿主的方式读。</b> 运行时的安全链只施加于 `cn.com.keelbase.runtime` 包里的路由，本控制器
 * 住在宿主包里，故走 {@link YudaoCallerId}（与 `SecurityShowcaseController` 同一个门）。
 *
 * <p>英文：
 *
 * <p>The narrow read face that turns the `userId` on an audit row into **a name or an account** — readable
 * by the audit reader too, and it answers with three things only.
 *
 * <p><b>Why the page does not just ask Yudao's directory.</b> An audit row carries only a `userId`
 * (the runtime's `AuditService.row()` sends no username), and `D49 ②` decided that "who" is filled in
 * from **the host's own directory**. But `/admin-api/system/user/list` needs `system:user:query`, and
 * `system_users` is also narrowed by Yudao's **table-keyed department data permission** — measured: an
 * account holding only `audit_reader` gets a `simple-list` containing **itself alone**. So handing it a
 * user-query permission would open more than was asked (the whole user-administration read face,
 * contact details included) and still would not name anybody else. This one face does only the thing.
 *
 * <p><b>The same door as the audit chain.</b> An administrator, or a non-administrator holding an
 * enabled `audit_reader` role ({@link YudaoAuditReaderRules}; that role table is the only authority).
 * Everyone else is answered 403. There is no third tier, and no request header is consulted.
 *
 * <p><b>Three fields only: id, account, nickname.</b> No email, mobile, department or roles — "who" wants
 * a name that can be said out loud, not the person's file. Data permission is switched off here **on
 * purpose** (`executeIgnore`): what the audit reader must name is precisely **other people's** rows, and a
 * department scope would narrow the answer back to the reader themselves. The scope of this face is set
 * by **authorization** (whoever may read audit rows may read who their callers were), not by row range.
 *
 * <p><b>The caller is read the host's way.</b> The runtime's security chain applies only to routes in
 * `cn.com.keelbase.runtime`; this controller lives in the host's package, so it reads the caller through
 * {@link YudaoCallerId} — the same door `SecurityShowcaseController` uses.
 */
@RestController
public class AuditCallerNamesController {

    private final YudaoCallerId callers;
    private final YudaoAdministrator administrators;
    private final YudaoAuditReaderRules readers;
    private final AdminUserService users;

    public AuditCallerNamesController(YudaoCallerId callers, YudaoAdministrator administrators,
                                      YudaoAuditReaderRules readers, AdminUserService users) {
        this.callers = callers;
        this.administrators = administrators;
        this.readers = readers;
        this.users = users;
    }

    /**
     * 给这些 id 配名。查不到的 id **不出现在答案里** —— 调用方按「没有这一条」处理，与读不出名字同一种降级。
     *
     * <p>Names for these ids. An id that cannot be read **is simply absent** from the answer; the caller
     * treats that the same way it treats "no name", which is the fallback both sides already have.
     */
    @GetMapping("/audit/caller-names")
    public List<Map<String, Object>> names(@RequestParam("ids") List<Long> ids) {
        requireAuditRead();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 租户按宿主的读法取（有就按宿主的、没有才回落到种子租户），与角色那条读法同源；数据权限在这里
        // 刻意关掉，理由见类注释。
        // The tenant is read the way the host reads it (the host's when there is one, the seed tenant as
        // the declared fallback), the same read the role lookup uses; data permission is switched off on
        // purpose here — see the class comment.
        List<AdminUserDO> found = TenantUtils.execute(YudaoCallerTenant.current(),
                () -> DataPermissionUtils.executeIgnore(() -> users.getUserList(ids)));
        return found.stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("nickname", user.getNickname());
            return row;
        }).toList();
    }

    /**
     * 管理员或审计读者；其余 403，未认证 401。两个都不是时**不解释**是谁——拒绝语句只说这一面要什么。
     *
     * <p>An administrator or an audit reader; everyone else 403, an unauthenticated caller 401. The
     * refusal does not say which of the two was missing.
     */
    private void requireAuditRead() {
        Long userId = callers.current();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (administrators.isAdmin(userId) || readers.isAuditReader(String.valueOf(userId))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this read face answers administrators and audit readers");
    }
}
