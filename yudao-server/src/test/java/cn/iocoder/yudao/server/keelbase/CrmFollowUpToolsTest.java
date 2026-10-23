// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.crm.controller.admin.followup.vo.CrmFollowUpRecordSaveReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.enums.common.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 旗舰闭环的两个出口：**该跟进谁**（读，有限且每行带理由）与**记一条跟进**（写，先受治理地读一次再写）。
 *
 * <p>读那半的三条判据在这里各有正反：成交的不出现；过期的排最前、从没联系过的其次、刚聊过的两边都不占；
 * 一次最多 10 行；出口只给时间与状态加一行算出来的理由，**联系方式与联系内容都不出现**。
 * 写那半钉的是「读不到与不是你的给同一个答案」、两个必填的拒绝、以及三个默认值（类型 1、下次 7 天后、
 * 认不出的日期也落默认而不是把整条丢掉）。
 *
 * <p>Both exits of the flagship loop: who is worth a follow-up (a read, capped and with a reason per
 * row) and recording one (a write that reads through the governance aspect first). For the read, each
 * of the three criteria has both directions here: a closed customer never appears, an overdue one ranks
 * first, never-contacted second, a recently-contacted one appears at neither end, the list caps at ten,
 * and the projection carries times, states and one computed reason — no contact details and no contact
 * content. For the write: "not found" and "not yours" answer the same, the two required fields are
 * refused, and three defaults hold (type 1, next time seven days out, an unreadable date falling back
 * rather than dropping the record).
 */
class CrmFollowUpToolsTest {

    private static final String CALLER = "42";
    private static final long CALLER_ID = 42L;

    private CrmCustomerService customers;
    private CrmFollowUpRecordService followUps;
    private CrmFollowUpTools.QueueTool queueTool;
    private CrmFollowUpTools.RecordTool recordTool;

    @BeforeEach
    void setUp() {
        customers = mock(CrmCustomerService.class);
        followUps = mock(CrmFollowUpRecordService.class);
        queueTool = new CrmFollowUpTools.QueueTool(customers);
        recordTool = new CrmFollowUpTools.RecordTool(customers, followUps);
    }

    @Test
    void aClosedCustomerIsNeverInTheQueue() {
        CrmCustomerDO closed = customer(1L);
        closed.setDealStatus(true);
        closed.setContactLastTime(LocalDateTime.now().minusDays(90));
        serveCustomers(List.of(closed));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queue = (List<Map<String, Object>>) queueTool.execute(Map.of(), caller()).data();

        assertTrue(queue.isEmpty(), "a customer already closed is not work");
    }

    @Test
    void theQueueOrdersOverdueThenNeverContactedThenStale() {
        LocalDateTime dueDate = LocalDateTime.now().minusDays(2);
        CrmCustomerDO overdue = customer(3L);
        overdue.setContactNextTime(dueDate);
        overdue.setContactLastTime(LocalDateTime.now().minusDays(20));
        CrmCustomerDO never = customer(2L);
        CrmCustomerDO stale = customer(1L);
        stale.setContactLastTime(LocalDateTime.now().minusDays(45));
        serveCustomers(List.of(stale, never, overdue));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queue = (List<Map<String, Object>>) queueTool.execute(Map.of(), caller()).data();

        assertEquals(List.of(3L, 2L, 1L), queue.stream().map(row -> row.get("id")).toList(),
                "overdue first, then never contacted, then the longest since");
        assertEquals("下次联系时间已过（" + dueDate.toLocalDate() + "）", queue.get(0).get("why"));
        assertEquals("还没有联系记录", queue.get(1).get("why"));
    }

    @Test
    void aFreshlyContactedCustomerIsNotInTheQueue() {
        CrmCustomerDO fresh = customer(1L);
        fresh.setContactLastTime(LocalDateTime.now().minusDays(1));
        serveCustomers(List.of(fresh));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queue = (List<Map<String, Object>>) queueTool.execute(Map.of(), caller()).data();

        assertTrue(queue.isEmpty());
    }

    @Test
    void theQueueStopsAtTenRows() {
        List<CrmCustomerDO> many = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            CrmCustomerDO row = customer(id);
            row.setContactLastTime(LocalDateTime.now().minusDays(60));
            many.add(row);
        }
        serveCustomers(many);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queue = (List<Map<String, Object>>) queueTool.execute(Map.of(), caller()).data();

        assertEquals(10, queue.size(), "a screenful, not another list");
    }

    @Test
    void theQueueProjectionCarriesNoContactDetails() {
        CrmCustomerDO row = customer(1L);
        row.setName("临海制造");
        row.setContactLastTime(LocalDateTime.now().minusDays(40));
        row.setMobile("13800000000");
        row.setEmail("buyer@example.com");
        serveCustomers(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queue = (List<Map<String, Object>>) queueTool.execute(Map.of(), caller()).data();

        assertEquals(List.of("id", "name", "level", "source", "lastContactAt", "nextContactAt", "why"),
                List.copyOf(queue.get(0).keySet()));
        assertTrue(queue.get(0).values().stream().noneMatch("13800000000"::equals));
    }

    @Test
    void theWriteRefusesWithoutTheRequiredFields() {
        assertFalse(recordTool.execute(Map.of("note", "打电话"), caller()).success());
        assertEquals("note is required", recordTool.execute(Map.of("id", 9), caller()).error());
        assertFalse(recordTool.execute(Map.of("id", 9), caller()).success());
    }

    @Test
    void aCustomerThatIsNotYoursAnswersTheSameAsOneThatIsNotThere() {
        when(customers.getCustomer(9L)).thenReturn(null);

        ToolResult result = recordTool.execute(Map.of("id", 9, "note", "打电话"), caller());

        assertFalse(result.success());
        assertEquals("customer not found, or not yours: 9", result.error(),
                "telling the two apart is itself the leak");
        verify(followUps, never()).createFollowUpRecord(any());
    }

    @Test
    void theWriteDefaultsTheTypeAndTheNextTime() {
        when(customers.getCustomer(9L)).thenReturn(customer(9L));
        when(followUps.createFollowUpRecord(any())).thenReturn(77L);

        ToolResult result = recordTool.execute(Map.of("id", 9, "note", "打了个电话"), caller());

        assertTrue(result.success());
        assertEquals(77L, ((Map<?, ?>) result.data()).get("id"));
        CrmFollowUpRecordSaveReqVO saved = savedRecord();
        assertEquals(CrmBizTypeEnum.CRM_CUSTOMER.getType(), saved.getBizType());
        assertEquals(9L, saved.getBizId());
        assertEquals(Integer.valueOf(1), saved.getType(), "type defaults to a phone call");
        assertEquals("打了个电话", saved.getContent());
        assertTrue(saved.getNextTime().isAfter(LocalDateTime.now().plusDays(6)),
                "no next time given means seven days out");
    }

    @Test
    void theWriteUsesTheCallersNextTimeWhenItParses() {
        when(customers.getCustomer(9L)).thenReturn(customer(9L));
        when(followUps.createFollowUpRecord(any())).thenReturn(77L);

        recordTool.execute(Map.of("id", 9, "note", "上门", "type", 3, "nextTime", "2026-11-05"), caller());

        CrmFollowUpRecordSaveReqVO saved = savedRecord();
        assertEquals(Integer.valueOf(3), saved.getType());
        assertEquals(LocalDate.of(2026, 11, 5).atStartOfDay(), saved.getNextTime());
    }

    @Test
    void anUnreadableNextTimeFallsBackRatherThanDroppingTheRecord() {
        when(customers.getCustomer(9L)).thenReturn(customer(9L));
        when(followUps.createFollowUpRecord(any())).thenReturn(77L);

        ToolResult result = recordTool.execute(
                Map.of("id", 9, "note", "微信沟通", "nextTime", "下周三"), caller());

        assertTrue(result.success(), "a recorded follow-up that is due again beats one that never landed");
        assertTrue(savedRecord().getNextTime().isAfter(LocalDateTime.now().plusDays(6)));
    }

    @Test
    void aWriteThatDoesNotLandIsReportedAsAFailure() {
        when(customers.getCustomer(9L)).thenReturn(customer(9L));
        when(followUps.createFollowUpRecord(any())).thenReturn(null);

        ToolResult result = recordTool.execute(Map.of("id", 9, "note", "打电话"), caller());

        assertFalse(result.success());
        assertEquals("the follow-up record could not be created", result.error());
    }

    @Test
    void theFacesDeclareTheirContract() {
        assertEquals("crm_follow_up_queue", queueTool.name());
        assertEquals("R1", queueTool.riskLevel());
        assertEquals("record_crm_followup", recordTool.name());
        assertEquals("R3", recordTool.riskLevel());
        assertEquals("crm_follow_up_record", recordTool.resultType());
    }

    private void serveCustomers(List<CrmCustomerDO> rows) {
        when(customers.getCustomerPage(any(), eq(CALLER_ID))).thenReturn(new PageResult<>(rows, (long) rows.size()));
    }

    private CrmFollowUpRecordSaveReqVO savedRecord() {
        ArgumentCaptor<CrmFollowUpRecordSaveReqVO> captor =
                ArgumentCaptor.forClass(CrmFollowUpRecordSaveReqVO.class);
        verify(followUps).createFollowUpRecord(captor.capture());
        return captor.getValue();
    }

    private static Principal caller() {
        return new Principal(CALLER, Principal.ROLE_USER);
    }

    private static CrmCustomerDO customer(long id) {
        CrmCustomerDO row = new CrmCustomerDO();
        row.setId(id);
        row.setName("客户 " + id);
        return row;
    }
}
