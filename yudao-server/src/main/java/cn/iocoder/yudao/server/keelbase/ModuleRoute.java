// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.List;
import java.util.Map;

/**
 * How a generated module's tool becomes reachable: the phrases it answers to, and the arguments a
 * match carries. One route per tool, declared next to it.
 *
 * <p>Host-agnostic — the same shape the RuoYi host used — because what it says is about the module,
 * not about the host. A planner the deployment supplies reads these; the runtime's default planner
 * does not know them, which is why supplying one is part of bringing a tool face over.
 *
 * 一个生成模块的工具**怎么变得够得到**：它回答哪些短语、一次命中的参数是什么。一个工具一条路由，与工具同处声明。
 *
 * <p>**与宿主无关**——与 RuoYi 宿主同形——因为它说的是**模块**的事、不是宿主的事。部署方提供的规划器读它们；
 * 运行时的默认规划器不认识它们——这正是「把工具面搬过来」为什么包含「提供一个规划器」。
 */
public interface ModuleRoute {

    /** The tool this route reaches. / 本条路由够到的工具。 */
    String toolName();

    /** Phrases that route here. / 路由到这里的短语。 */
    List<String> triggers();

    /** The arguments a match carries, read from the turn's context. / 一次命中携带的参数，取自本轮的上下文。 */
    Map<String, Object> args(Map<String, Object> context);
}
