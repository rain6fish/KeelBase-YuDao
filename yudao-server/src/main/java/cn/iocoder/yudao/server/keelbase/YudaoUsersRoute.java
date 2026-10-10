// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import cn.com.keelbase.runtime.pipeline.ModuleRoute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The phrases Yudao's user tool answers to. Declared beside the tool so the pair reads as one thing —
 * which is the point of a route being a separate object rather than a constant inside the planner.
 *
 * 芋道用户工具回答的短语。与工具同处声明，好让这对读作一件事——这正是「路由是一个独立对象、而不是规划器里
 * 一个常量」的意义。
 */
@Component
public class YudaoUsersRoute implements ModuleRoute {

    @Override
    public String toolName() {
        return "list_yudao_users";
    }

    @Override
    public List<String> triggers() {
        return List.of("芋道用户", "系统用户", "管理端用户", "列出用户", "用户列表");
    }

    @Override
    public Map<String, Object> args(Map<String, Object> context) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (context != null && context.get("nickname") != null) {
            args.put("nickname", context.get("nickname"));
        }
        return args;
    }
}
