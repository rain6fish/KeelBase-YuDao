// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.module.crm.dal.mysql.customer.CrmCustomerMapper;
import cn.iocoder.yudao.module.crm.dal.mysql.followup.CrmFollowUpRecordMapper;
import cn.iocoder.yudao.module.fms.dal.mysql.voucher.FmsVoucherMapper;
import cn.iocoder.yudao.module.infra.dal.mysql.demo.demo01.Demo01ContactMapper;
import cn.iocoder.yudao.module.products.dal.mysql.products.ProductsMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Answers "is the record this write landed on still there?" for the host's own result types.
 *
 * <p>The runtime's {@code EffectController.view()} decides {@code targetExists} from its own only
 * local entity, {@code follow_up}. A host write names a result type the runtime has never heard of
 * ({@code crm_customer}, {@code products}, ...), so that lookup is empty and every host row reached
 * the console as "no longer there" — the Proxy-1 miss on 2026-10-21, where customer #1 was still in
 * the table while its ledger row said it was gone.
 *
 * <p>The host is the only side that knows where those rows live, so this advice corrects the field
 * on the way out: for each result type this host compensates, ask that module's mapper whether the
 * row exists. It changes nothing else — the runtime still decides the scope, the revocability and
 * the refusal, and a row whose target really was deleted (a completed revoke) still answers false.
 * Unknown result types are left exactly as the runtime answered them; this class does not invent a
 * verdict for a table it cannot read.
 *
 * <p>It runs before the runtime's envelope advice ({@link Ordered#HIGHEST_PRECEDENCE}), because after
 * wrapping the body is an envelope rather than the row list this class reads.
 *
 * 回答「这次写落在的那条记录还在不在」——针对宿主自己的结果类型。
 *
 * <p>运行时的 `EffectController.view()` 只用它自己唯一的本地实体 `follow_up` 判 `targetExists`。
 * 宿主的写用的是运行时从未听说过的结果类型（`crm_customer`、`products`……），那次查找因此为空，
 * 每一行宿主记录到了控制台都成了「已不在」—— 这正是 2026-10-21 Proxy-1 那次误报：客户 #1 还在表里，
 * 台账行却说它没了。
 *
 * <p>只有宿主知道那些行住在哪，故这条 advice 在出口处更正这个字段：对每一种本宿主要补偿的结果类型，
 * 问那个模块的 mapper 这一行在不在。别的一概不动 —— 范围、可撤性、拒绝仍由运行时裁决；目标真被删掉
 * （一次撤销落成）仍旧答 false。本类不认识的结果类型原样保留运行时的答案；它不会为一张自己读不了的
 * 表编一个判决。
 *
 * <p>它排在运行时信封 advice **之前**（{@link Ordered#HIGHEST_PRECEDENCE}）：包起来之后正文是信封，
 * 不再是本类要读的行列表。
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class YudaoEffectTargetExistence implements ResponseBodyAdvice<Object> {

    /** The runtime endpoint whose rows carry `targetExists`. */
    /** 那些行带 `targetExists` 的运行时端点。 */
    static final String EFFECTS_PATH = "/ai/tool-effects";

    /** One module's answer to "is this row still there?" — a mapper read, never a re-derivation. */
    /** 一个模块对「这一行还在吗？」的答复 —— 一次 mapper 读，不是重推。 */
    @FunctionalInterface
    interface TargetProbe {
        boolean exists(long id);
    }

    private final Map<String, TargetProbe> probes;

    public YudaoEffectTargetExistence(CrmCustomerMapper customers,
                                      CrmFollowUpRecordMapper followUps,
                                      FmsVoucherMapper vouchers,
                                      Demo01ContactMapper contacts,
                                      ProductsMapper products) {
        Map<String, TargetProbe> found = new LinkedHashMap<>();
        // The result types `YudaoLocalCompensators` serves, and only those: a type with no
        // compensator is not a type this host can say anything about.
        // 只覆盖 `YudaoLocalCompensators` 服务的那些结果类型：没有补偿器的类型，本宿主说不出什么。
        found.put("crm_customer", id -> customers.selectById(id) != null);
        found.put("crm_follow_up_record", id -> followUps.selectById(id) != null);
        found.put("fms_voucher", id -> vouchers.selectById(id) != null);
        found.put("infra_demo01_contact", id -> contacts.selectById(id) != null);
        found.put("products", id -> products.selectById(id) != null);
        this.probes = Map.copyOf(found);
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // This Spring's contract asks `supports` without the request, so the path is checked in
        // `beforeBodyWrite`; every other response passes through untouched.
        // 本版 Spring 的 `supports` 不带请求，故路径在 `beforeBodyWrite` 里判；其余响应原样通过。
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (HttpMethod.GET.equals(request.getMethod())
                && request.getURI().getPath().endsWith(EFFECTS_PATH)) {
            correct(body);
        }
        return body;
    }

    /**
     * Corrects `targetExists` in place for every row whose result type has a probe.
     *
     * <p>Only the two fields this class can back are touched. A non-numeric id, an unknown type, or
     * a body that is not the ledger's page all leave the runtime's answer as it stands — the
     * alternative is a second, quieter lie in place of the first.
     *
     * 就地更正每一行的 `targetExists` —— 仅限那些结果类型有探针的行。
     *
     * <p>只碰本类撑得住的那两个字段。非数字 id、未知类型、或者根本不是台账页面的正文，都原样保留
     * 运行时的答案 —— 另一条路是拿第二个更安静的谎去换掉第一个。
     */
    public void correct(Object body) {
        if (!(body instanceof Map<?, ?> page)) {
            return;
        }
        Object items = page.get("items");
        if (!(items instanceof List<?> rows)) {
            return;
        }
        for (Object candidate : rows) {
            if (candidate instanceof Map<?, ?> raw) {
                correctRow(raw);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void correctRow(Map<?, ?> raw) {
        Map<String, Object> row = (Map<String, Object>) raw;
        Object resultType = row.get("resultType");
        if (!(resultType instanceof String type)) {
            return;
        }
        TargetProbe probe = probes.get(type);
        if (probe == null) {
            return;
        }
        Long id = asId(row.get("resultId"));
        if (id == null) {
            return;
        }
        boolean exists;
        try {
            exists = probe.exists(id);
        } catch (RuntimeException unreadable) {
            // A failing read is not evidence that the row is gone. Leave the runtime's answer rather
            // than turn an outage into another wrong "no longer there".
            // 读失败不是「行没了」的证据。保留运行时的答案，而不是把一次故障变成又一个错的「已不在」。
            return;
        }
        row.put("targetExists", exists);
        if (!exists) {
            // The mapper does not see soft-deleted rows either; "not there" is the fact either way.
            // mapper 同样看不见软删的行；无论哪种，「不在」都是事实。
            row.put("targetSoftDeleted", false);
        }
    }

    private static Long asId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException notAnId) {
                return null;
            }
        }
        return null;
    }
}
