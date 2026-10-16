// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.crm.controller.admin.customer.vo.customer.CrmCustomerPageReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.followup.vo.CrmFollowUpRecordSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.enums.common.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 旗舰闭环的两个工具：**该跟进谁**（读）与**记一条跟进**（写）。
 *
 * <p>这一对存在的理由，是把「助手替你干一件真事」拆成可被治理的两半，而不是把一件大事塞进一个工具里：
 *
 * <ul>
 *   <li><b>读那半给的是**判断的输入**，不是判决。</b>{@code crm_customer} 自己就带着「上次联系」「下次联系」
 *       「是否待跟进」「是否成交」这几个字段，故「为什么值得跟进」可以**算出来**、并逐行写下理由 —— 这是
 *       「有限、可解释」那两条要求的落点：**限定条数**（{@link #QUEUE_LIMIT}）且**每行带 why**。排序与取舍
 *       留给助手（模型）说，工具只负责把**权限内的、相关的**事实递过去。</li>
 *   <li><b>写那半走模块**自己**的业务对象。</b>跟进记录是 CRM 自带的 {@code crm_follow_up_record}，故这里调的
 *       是模块自己的 {@code CrmFollowUpRecordService} —— 与 {@link CrmCustomerTools} 调它自己的 service 同一条
 *       道理：**在事情被做的地方治理**，而不是另写一条通路。</li>
 * </ul>
 *
 * <p><b>属主怎么判。</b>工具先按 id 读一次客户，而那一读是**已受治理**的（
 * {@link CrmCustomerGovernance} 在 {@code CrmCustomerService.getCustomer} 上有一层切面）⇒ 不属于调用方的那一行
 * 在那里就返回 null ⇒ 这里直接拒绝。读不到与不是你的，**给同一个答案**，因为区分它们本身就是泄漏。
 *
 * <p><b>投影的边界没变。</b>联系方式（手机/电话/微信/邮箱/地址）与联系**内容**一律不外流；出去的是**时间与
 * 状态**，加上一行**由这些字段算出来的理由**。
 *
 * The flagship loop's two tools: who is worth following up (a read) and recording a follow-up (a write).
 *
 * <p>They exist to split "the assistant does a real thing for you" into two halves that can each be
 * governed, rather than putting one large act behind one tool. The read supplies the *inputs* to a
 * judgement rather than the judgement: `crm_customer` already carries when it was last contacted,
 * when it is next due, whether it is marked for follow-up and whether it closed — so "why is this one
 * worth a call" can be **computed** and written per row. That is where "limited and explainable" lands:
 * a cap on rows, and a `why` on each. Ranking and wording are the assistant's to do; the tool hands
 * over the permitted, relevant facts. The write goes through the module's **own** object
 * (`crm_follow_up_record`) and its own service, for the same reason the customer tools call theirs:
 * govern where the thing is done. Ownership is settled by reading the customer first — a read the
 * customer governance aspect already holds — so a row that is not the caller's answers null there and
 * the tool refuses here; "not found" and "not yours" get the same answer, because telling them apart
 * is itself the leak. Contact details and contact *content* stay out; what leaves is times and states,
 * plus one line of reason computed from them.
 */
@Configuration
public class CrmFollowUpTools {

    /** 一屏能读完的条数。超过它，「可解释」就退化成「又一份列表」。 */
    private static final int QUEUE_LIMIT = 10;

    /** 多久没联系算「该跟了」。挑 30 天是因为它把「本月没碰过」与「昨天刚聊过」分开。 */
    private static final long STALE_DAYS = 30;

    /** `crm_follow_up_type` 的缺省值：1 打电话。取值见该字典（1 打电话 / 2 发短信 / 3 上门拜访 / 4 微信沟通）。 */
    private static final int DEFAULT_FOLLOW_UP_TYPE = 1;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 不写下次联系时间时，默认几天后再联系。 */
    private static final long DEFAULT_NEXT_DAYS = 7;

    @Bean
    AiTool crmFollowUpQueue(CrmCustomerService customers) {
        return new QueueTool(customers);
    }

    @Bean
    AiTool recordCrmFollowUp(CrmCustomerService customers, CrmFollowUpRecordService followUps) {
        return new RecordTool(customers, followUps);
    }

    @Bean
    ModuleRoute crmFollowUpQueueRoute() {
        return new QueueRoute();
    }

    @Bean
    ModuleRoute recordCrmFollowUpRoute() {
        return new RecordRoute();
    }

    /** R1 —— 读：无需确认、无可撤销。 */
    static final class QueueTool implements AiTool {

        private final CrmCustomerService service;

        QueueTool(CrmCustomerService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "crm_follow_up_queue";
        }

        @Override
        public String description() {
            return "Which of THIS caller's customers are worth following up next, and why — at most "
                    + QUEUE_LIMIT + " rows, each carrying a reason computed from when it was last "
                    + "contacted, when it is next due, whether it is marked for follow-up and whether "
                    + "it closed. A read: it proposes nothing and writes nothing. Contact details and "
                    + "the content of past contacts are not projected.";
        }

        @Override
        public String riskLevel() {
            return "R1";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            CrmCustomerPageReqVO query = new CrmCustomerPageReqVO();
            query.setPageSize(100);
            Long caller = asId(principal.userId());
            List<Map<String, Object>> queue = ActingCaller.as(principal.userId(),
                            () -> TenantUtils.execute(YudaoCallerTenant.current(),
                                    () -> service.getCustomerPage(query, caller))).getList().stream()
                    .filter(row -> worthAFollowUp(row, LocalDateTime.now()))
                    .sorted(byUrgency(LocalDateTime.now()))
                    .limit(QUEUE_LIMIT)
                    .map(CrmFollowUpTools::project)
                    .toList();
            return ToolResult.ok(queue);
        }
    }

    /** R3 —— 写：引擎扣下等人。 */
    static final class RecordTool implements AiTool {

        private final CrmCustomerService customers;
        private final CrmFollowUpRecordService followUps;

        RecordTool(CrmCustomerService customers, CrmFollowUpRecordService followUps) {
            this.customers = customers;
            this.followUps = followUps;
        }

        @Override
        public String name() {
            return "record_crm_followup";
        }

        @Override
        public String description() {
            return "Record a follow-up against a customer of THIS caller's. The engine holds the call "
                    + "for a human; a customer outside the caller's scope is refused even after "
                    + "approval. The customer id travels in the turn's target slot.";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return "crm_follow_up_record";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.required("id", ToolParameter.NUMBER, "客户编号"),
                    ToolParameter.required("note", ToolParameter.STRING, "这次跟进的内容"),
                    ToolParameter.optional("type", ToolParameter.NUMBER,
                            "跟进方式（字典 crm_follow_up_type：1 打电话 / 2 发短信 / 3 上门拜访 / 4 微信沟通）"),
                    ToolParameter.optional("nextTime", ToolParameter.STRING,
                            "下次联系时间（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）；不传则默认 7 天后"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Principal principal) {
            Long id = asLong(args.get("id"));
            if (id == null) {
                return ToolResult.fail("id is required and must be a number");
            }
            String note = str(args.get("note"));
            if (note == null || note.isBlank()) {
                return ToolResult.fail("note is required");
            }
            // 先按 id 读一次：那一读带治理切面，读到 null 既可能是没有、也可能不是你的 —— 而这两者给同一个答案。
            // Read it first: that read carries the governance aspect, and null means either "no such row"
            // or "not yours" — which get the same answer on purpose.
            CrmCustomerDO customer = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(), () -> customers.getCustomer(id)));
            if (customer == null) {
                return ToolResult.fail("customer not found, or not yours: " + id);
            }
            Integer type = asInt(args.get("type"));
            CrmFollowUpRecordSaveReqVO record = new CrmFollowUpRecordSaveReqVO();
            record.setBizType(CrmBizTypeEnum.CRM_CUSTOMER.getType());
            record.setBizId(id);
            record.setType(type == null ? DEFAULT_FOLLOW_UP_TYPE : type);
            record.setContent(note);
            // 模块自己的 save VO **要求**下次联系时间。给一个**说出来的**默认（7 天后），而不是拿它的字段去猜：
            // 一条不写下次的跟进记录是个死胡同，而「什么时候再联系」正是这次记录存在的理由。
            //
            // The module's own save VO **requires** the next contact time. It gets a **stated** default of
            // seven days rather than a guess at its fields: a follow-up that names no next time is a dead
            // end, and "when to come back" is what the record is for.
            record.setNextTime(nextTimeOrDefault(args.get("nextTime")));
            Long recordId = ActingCaller.as(principal.userId(),
                    () -> TenantUtils.execute(YudaoCallerTenant.current(),
                            () -> followUps.createFollowUpRecord(record)));
            if (recordId == null) {
                return ToolResult.fail("the follow-up record could not be created");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", recordId);
            return ToolResult.ok(out);
        }
    }

    /** 本模块读所回答的短语。 */
    static final class QueueRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "crm_follow_up_queue";
        }

        @Override
        public List<String> triggers() {
            return List.of("该跟进哪些客户", "跟进哪些客户", "哪些客户该跟进", "本周该跟进", "跟进优先级");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            return Map.of();
        }
    }

    /** 本模块写所回答的短语；要写的那一行取本轮点名的那个对象，内容取调用方的原话。 */
    static final class RecordRoute implements ModuleRoute {

        @Override
        public String toolName() {
            return "record_crm_followup";
        }

        @Override
        public List<String> triggers() {
            return List.of("记一条跟进", "建一条跟进记录", "记录跟进", "跟进记录");
        }

        @Override
        public Map<String, Object> args(Map<String, Object> context) {
            Map<String, Object> args = new LinkedHashMap<>();
            // 那一行以通用槽位传递（见 `ChatRequest`）；内容用调用方的原话，与本模块其余路由同法。
            // The row travels in the generic slot (see `ChatRequest`); the content is the caller's own
            // words, as this module's other routes do it.
            if (context.get("targetId") != null) {
                args.put("id", context.get("targetId"));
            }
            args.put("note", context.get("message"));
            return args;
        }
    }

    /**
     * 这一行**值不值得跟**：没成交，且「被标为待跟进」「下次联系已过期」「太久没联系」「从没联系过」占其一。
     *
     * Whether this row is **worth a follow-up**: not closed, and one of "marked for follow-up", "the
     * next contact is overdue", "it has been too long" or "never contacted" holds.
     */
    private static boolean worthAFollowUp(CrmCustomerDO row, LocalDateTime now) {
        if (Boolean.TRUE.equals(row.getDealStatus())) {
            return false;
        }
        return Boolean.TRUE.equals(row.getFollowUpStatus())
                || (row.getContactNextTime() != null && row.getContactNextTime().isBefore(now))
                || row.getContactLastTime() == null
                || row.getContactLastTime().isBefore(now.minusDays(STALE_DAYS));
    }

    /**
     * 越急的排前面：**已过期**的最急，其次「从没联系过」，再次「离得最久」。同一档内按 id 稳定排序，好让两次
     * 运行给出同一个序。
     *
     * Most urgent first: an overdue date, then never-contacted, then longest-since. Ties break on id so
     * two runs give the same order.
     */
    private static Comparator<CrmCustomerDO> byUrgency(LocalDateTime now) {
        return Comparator
                .comparingInt((CrmCustomerDO row) -> row.getContactNextTime() != null
                        && row.getContactNextTime().isBefore(now) ? 0
                        : row.getContactLastTime() == null ? 1 : 2)
                .thenComparing(row -> row.getContactLastTime() == null ? LocalDateTime.MIN
                        : row.getContactLastTime())
                .thenComparing(CrmCustomerDO::getId);
    }

    /** 出去的那些字段 —— 投影就是**这个决定**可读的地方。 */
    private static Map<String, Object> project(CrmCustomerDO row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", row.getId());
        safe.put("name", row.getName());
        safe.put("level", row.getLevel());
        safe.put("source", row.getSource());
        safe.put("lastContactAt", row.getContactLastTime() == null ? null : row.getContactLastTime().toLocalDate().format(DAY));
        safe.put("nextContactAt", row.getContactNextTime() == null ? null : row.getContactNextTime().toLocalDate().format(DAY));
        safe.put("why", why(row, LocalDateTime.now()));
        return safe;
    }

    /** 一行理由，由上面那些字段**算出来** —— 不含联系内容，也不含任何联系方式。 */
    private static String why(CrmCustomerDO row, LocalDateTime now) {
        if (row.getContactNextTime() != null && row.getContactNextTime().isBefore(now)) {
            return "下次联系时间已过（" + row.getContactNextTime().toLocalDate().format(DAY) + "）";
        }
        if (row.getContactLastTime() == null) {
            return "还没有联系记录";
        }
        long days = Duration.between(row.getContactLastTime(), now).toDays();
        if (Boolean.TRUE.equals(row.getFollowUpStatus())) {
            return "被标为待跟进，距上次联系 " + days + " 天";
        }
        return "距上次联系 " + days + " 天";
    }

    /**
     * 下次联系时间：调用方给了就用它（`yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss`），没给就 **7 天后**。认不出的值也落到
     * 默认上、而不是抛 —— 一条**记下来、下次还要联系**的跟进，好过一条因为日期写法不对而根本没落下的跟进。
     *
     * The next contact time: the caller's value when it parses (`yyyy-MM-dd` or `yyyy-MM-dd HH:mm:ss`),
     * seven days out otherwise. An unreadable value falls back too rather than throwing — a follow-up that
     * is recorded and due again beats one that never landed because a date was spelled differently.
     */
    private static LocalDateTime nextTimeOrDefault(Object value) {
        String text = str(value);
        if (text != null && !text.isBlank()) {
            String trimmed = text.trim();
            try {
                return trimmed.length() <= 10
                        ? LocalDate.parse(trimmed).atStartOfDay()
                        : LocalDateTime.parse(trimmed.replace(' ', 'T'));
            } catch (DateTimeParseException notADate) {
                // 落到默认，而不是把这次跟进整条丢掉。
            }
        }
        return LocalDateTime.now().plusDays(DEFAULT_NEXT_DAYS);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    private static Long asId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
