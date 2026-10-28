// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 台账「谁」那一列的名字来源：一条只读端点，**不要求任何额外权限**——已登录即可问，问得到的范围由
 * {@link YudaoCallerDirectory} 按「这个调用方本来就能在台账里遇到的人」裁剪。
 *
 * <p>它为什么是一条新端点而不是给读者补权限：用户 2026-10-27 裁定走「服务端联名」，即把 id → 姓名这次
 * **联结**交给服务端，读者的授予一条不动（M7 第三位读者场 F-14）。控制台见 `callerNames.ts`。
 *
 * <p>英文：
 *
 * <p>Where the ledger's "who" column gets its names: one read-only endpoint that **requires no extra
 * permission** — any signed-in caller may ask, and {@link YudaoCallerDirectory} decides how far the answer
 * goes (only people this caller could already meet in a ledger).
 *
 * <p>Why a new endpoint rather than another grant for the readers: on 2026-10-27 the user chose the
 * server-side join — the id → name step moves to the server and no reader's grant changes (finding F-14,
 * M7's third reader session). The console side is `callerNames.ts`.
 */
@RestController
public class YudaoCallerNamesController {

    private final YudaoCallerDirectory directory;
    private final YudaoCallerId callers;

    public YudaoCallerNamesController(YudaoCallerDirectory directory, YudaoCallerId callers) {
        this.directory = directory;
        this.callers = callers;
    }

    /**
     * `GET /admin-api/keelbase/caller-names?ids=9001,9002` → `[{id, name}]`，只含这个调用方可以知道名字的那些。
     *
     * <p>ids 解析不出来的部分被丢掉而不是报错：这条端点做的是**补名字**，页面本来就同时画着 id。
     *
     * <p>`GET .../caller-names?ids=...` → `[{id, name}]` for those this caller may name. Ids that do not
     * parse are dropped rather than rejected: the endpoint only adds names, and the page draws the ids
     * anyway.
     */
    @GetMapping("/admin-api/keelbase/caller-names")
    public CommonResult<List<Map<String, Object>>> names(
            @RequestParam(value = "ids", required = false) String ids) {
        Map<String, String> names = directory.namesFor(callers.current(), parse(ids));
        List<Map<String, Object>> body = new ArrayList<>(names.size());
        for (Map.Entry<String, String> entry : names.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getKey());
            row.put("name", entry.getValue());
            body.add(row);
        }
        return CommonResult.success(body);
    }

    /** 只收十进制数字 id，去重，最多 {@link YudaoCallerDirectory#MAX_IDS} 个。 / decimal ids only, deduped, capped. */
    static List<Long> parse(String ids) {
        List<Long> parsed = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return parsed;
        }
        for (String piece : ids.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty() || trimmed.length() > 18 || !trimmed.chars().allMatch(Character::isDigit)) {
                continue;
            }
            Long id = Long.valueOf(trimmed);
            if (id > 0 && !parsed.contains(id)) {
                parsed.add(id);
            }
            if (parsed.size() >= YudaoCallerDirectory.MAX_IDS) {
                break;
            }
        }
        return parsed;
    }
}
