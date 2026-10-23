// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 控制台导航目录与它的那个工具：页键**唯一**、每条路由可去、认不出的键**拒绝并点名**。
 *
 * <p>目录是数据，工具只是查表，故这里的断言都在数据自身的性质上——键重复会让两页抢同一个名字，路由空着会让
 * 「导航成功」落到一个不存在的地址。
 *
 * <p>The console navigation catalogue and the tool over it: keys unique, every route visitable, and an
 * unknown key refused by name. The catalogue is data and the tool only looks it up, so the assertions
 * are about the data itself — a duplicate key makes two pages compete for one name, and an empty route
 * would let "navigated" land on an address that does not exist.
 */
class ConsolePagesTest {

    private final ConsolePages.NavigateTool tool = new ConsolePages.NavigateTool();

    @Test
    void everyPageKeyIsUniqueAndEveryRouteIsAPath() {
        Set<String> keys = new HashSet<>();
        for (ConsolePages.Page page : ConsolePages.PAGES) {
            assertTrue(keys.add(page.key()), "duplicate page key: " + page.key());
            assertFalse(page.route().isBlank(), page.key() + " has no route");
            assertTrue(page.route().startsWith("/"), page.key() + " does not route to a path");
            assertFalse(page.name().isBlank(), page.key() + " has no display name");
        }
    }

    @Test
    void navigatingToAKnownPageAnswersWithItsRoute() {
        ToolResult result = tool.execute(Map.of("page", "crm-customers"), principal());

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals("crm-customers", data.get("pageName"));
        assertEquals(3, data.size(), "the route, the page name and its description — nothing else");
        assertTrue(data.values().stream().anyMatch("/crm/customer"::equals));
    }

    @Test
    void anUnknownPageIsRefusedByName() {
        ToolResult result = tool.execute(Map.of("page", "no-such-page"), principal());

        assertFalse(result.success());
        assertTrue(result.error().contains("unknown page: no-such-page"),
                "the refusal names what was asked for");
    }

    @Test
    void askingForNothingIsTheSameRefusal() {
        assertFalse(tool.execute(Map.of(), principal()).success());
    }

    private static Principal principal() {
        return new Principal("42", Principal.ROLE_USER);
    }
}
