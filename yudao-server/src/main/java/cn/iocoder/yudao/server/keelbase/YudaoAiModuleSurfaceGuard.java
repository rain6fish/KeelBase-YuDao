// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 芋道自带 AI 模块（`yudao-module-ai`）的 **HTTP 应用面**在宿主这里整体关停。
 *
 * <p>`D52 ①` 弃用的是这个模块的**应用面**，不是它的代码：模块仍随 jar 编译（其 `AiModelService` 等服务类
 * 仍在），但对话 / 绘画 / 音乐 / 写作 / 导图 / 工作流 / 知识库以及各管理页都已从菜单上撤下，其表也不在
 * 本部署的种子里。若这些路由继续可达，它们会以「表结构未导入」的 500 作答 —— 一个已弃用的应用面不该以
 * 500 的形态存在。故这里把 `/admin-api/ai/**` 一律答 404：与「本部署没跑这个模块」对外同形。
 *
 * <p>它不是权限判定：审计读者在 `YudaoAuditReaderExecutionGuard` 里仍先得它自己的 403（注册顺序在
 * 本拦截器之前），本拦截器只对已经通过前面守卫的调用方回答「这个面不存在」。
 *
 * <p>English:
 *
 * <p>The built-in AI module's **HTTP application surface** is switched off at the host.
 *
 * <p>`D52 ①` deprecates the module's **application surface**, not its code: the module still compiles into
 * the jar (its service classes remain), but chat, image, music, write, mind map, workflow, knowledge and
 * their manager pages are gone from the menus, and its tables are not in this deployment's seed. If those
 * routes stayed reachable they would answer 500 "tables not imported" — a deprecated surface should not
 * exist in the shape of a 500. So every `/admin-api/ai/**` answers 404 here: the same shape as "this
 * deployment does not run that module".
 *
 * <p>It is not a permission decision: an audit reader still receives its own 403 from
 * `YudaoAuditReaderExecutionGuard` first (that registration precedes this one); this interceptor only
 * tells callers that already passed the earlier guards that the surface does not exist.
 */
@Component
public class YudaoAiModuleSurfaceGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        response.sendError(HttpServletResponse.SC_NOT_FOUND,
                "the built-in AI module's application surface is switched off (D52)");
        return false;
    }
}
