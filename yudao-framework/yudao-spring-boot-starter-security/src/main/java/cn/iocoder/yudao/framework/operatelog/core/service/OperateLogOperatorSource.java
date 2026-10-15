// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.framework.operatelog.core.service;

import cn.iocoder.yudao.framework.security.core.LoginUser;

/**
 * 「谁」在一次**不经请求**的写里去哪儿问 —— 一条接缝，不是第二套身份来源。
 *
 * <p>操作日志的「谁」一直取自请求自己的安全上下文（{@code SecurityFrameworkUtils}）。对过滤器链认证过的请求
 * 这是对的；对**不经请求**的那些写，它是**空的**：引擎的工具路径跑在安全过滤器链之外，而它手里的调用方由别处
 * 发布。于是一条由 AI 写下的业务记录，在宿主自己的操作日志里**不挂人** —— 而审计最需要「谁」的，恰恰是那些行。
 *
 * <p>这条接缝只回答**去哪儿问**。次序、回落与解析属于**部署方**（一个宿主从它的安全上下文读，另一个从发布调用方
 * 的地方读），故那段算术写在**实现**里、不在这里。没有实现时本接缝**不存在**，行为与从前一字不差 —— 一个说不出
 * 调用者的行**如实留空**，而不是编一个出来。
 */
public interface OperateLogOperatorSource {

    /**
     * 本次调用的操作者；说不出时给 {@code null}（这一行于是如实不挂人）。
     */
    LoginUser currentOperator();

}
