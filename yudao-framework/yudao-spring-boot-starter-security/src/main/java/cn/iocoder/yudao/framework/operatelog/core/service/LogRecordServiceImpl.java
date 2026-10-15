package cn.iocoder.yudao.framework.operatelog.core.service;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 操作日志 ILogRecordService 实现类
 *
 * 基于 {@link OperateLogCommonApi} 实现，记录操作日志
 *
 * @author HUIHUI
 */
@Slf4j
public class LogRecordServiceImpl implements ILogRecordService {

    @Resource
    private OperateLogCommonApi operateLogApi;

    /**
     * 请求自己说不出调用方时，由它作答；宿主没有提供时它是 {@code null}，行为与从前一致。
     */
    @Autowired(required = false)
    private OperateLogOperatorSource operatorSource;

    @Override
    public void record(LogRecord logRecord) {
        OperateLogCreateReqDTO reqDTO = new OperateLogCreateReqDTO();
        try {
            reqDTO.setTraceId(TracerUtils.getTraceId());
            // 补充用户信息
            fillUserFields(reqDTO);
            // 补全模块信息
            fillModuleFields(reqDTO, logRecord);
            // 补全请求信息
            fillRequestFields(reqDTO);

            // 2. 异步记录日志
            operateLogApi.createOperateLogAsync(reqDTO);
        } catch (Throwable ex) {
            // 由于 @Async 异步调用，这里打印下日志，更容易跟进
            log.error("[record][url({}) log({}) 发生异常]", reqDTO.getRequestUrl(), reqDTO, ex);
        }
    }

    private void fillUserFields(OperateLogCreateReqDTO reqDTO) {
        // 使用 SecurityFrameworkUtils。因为要考虑，rpc、mq、job，它其实不是 web；
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null) {
            // 请求自己的身份为空：问那条接缝。AI 的工具路径跑在安全过滤器链之外，它的调用方由别处发布 ⇒
            // 少了这一步，AI 写下的那行日志在「谁」这一列上是空的，而那正是审计最要的一列。
            loginUser = actingOperator();
        }
        if (loginUser == null) {
            return;
        }
        reqDTO.setUserId(loginUser.getId());
        reqDTO.setUserType(loginUser.getUserType());
    }

    /** 宿主那条接缝说的操作者；宿主没提供时给 {@code null}。 */
    private LoginUser actingOperator() {
        return operatorSource == null ? null : operatorSource.currentOperator();
    }

    public static void fillModuleFields(OperateLogCreateReqDTO reqDTO, LogRecord logRecord) {
        reqDTO.setType(logRecord.getType()); // 大模块类型，例如：CRM 客户
        reqDTO.setSubType(logRecord.getSubType());// 操作名称，例如：转移客户
        reqDTO.setBizId(Long.parseLong(logRecord.getBizNo())); // 业务编号，例如：客户编号
        reqDTO.setAction(logRecord.getAction());// 操作内容，例如：修改编号为 1 的用户信息，将性别从男改成女，将姓名从芋道改成源码。
        reqDTO.setExtra(logRecord.getExtra()); // 拓展字段，有些复杂的业务，需要记录一些字段 ( JSON 格式 )，例如说，记录订单编号，{ orderId: "1"}
    }

    private static void fillRequestFields(OperateLogCreateReqDTO reqDTO) {
        // 获得 Request 对象
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) {
            return;
        }
        // 补全请求信息
        reqDTO.setRequestMethod(request.getMethod());
        reqDTO.setRequestUrl(request.getRequestURI());
        reqDTO.setUserIp(ServletUtils.getClientIP(request));
        reqDTO.setUserAgent(ServletUtils.getUserAgent(request));
    }

    @Override
    public List<LogRecord> queryLog(String bizNo, String type) {
        throw new UnsupportedOperationException("使用 OperateLogApi 进行操作日志的查询");
    }

    @Override
    public List<LogRecord> queryLogByBizNo(String bizNo, String type, String subType) {
        throw new UnsupportedOperationException("使用 OperateLogApi 进行操作日志的查询");
    }

}