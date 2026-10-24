package cn.iocoder.yudao.module.ai1.dal.mysql.home;

import cn.iocoder.yudao.module.ai1.controller.admin.home.vo.Ai1HomeMessageSummaryByAgentRespVO;
import cn.iocoder.yudao.module.ai1.controller.admin.home.vo.Ai1HomeMessageSummaryByDateRespVO;
import cn.iocoder.yudao.module.ai1.controller.admin.home.vo.Ai1HomeSummaryRespVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI1 首页统计 Mapper
 *
 * @author 芋道源码
 */
@Mapper
public interface Ai1HomeMapper {

    // 别名不加反引号、`deleted` 与 0 比较而不是 FALSE：这条线部署在 PostgreSQL 上——反引号是语法错误，
    // 而 `deleted` 是 smallint，`deleted = FALSE` 会报「operator does not exist: smallint = boolean」。
    // 排序键改用位置序号，绕开保留字。这三种写法 MySQL 与 H2 也认。
    // No backtick-quoted aliases and `deleted` compared to 0 rather than FALSE: this line deploys on
    // PostgreSQL, where backticks are a syntax error and `deleted` is smallint, so `deleted = FALSE`
    // fails with "operator does not exist: smallint = boolean". Positional ORDER BY keeps the sort
    // keys free of reserved words. All three forms are read by MySQL and H2 too.
    @Select("SELECT (SELECT COUNT(1) FROM ai1_agent WHERE deleted = 0) AS agentCount, " +
            "(SELECT COUNT(1) FROM ai1_skill WHERE deleted = 0) AS skillCount, " +
            "(SELECT COUNT(1) FROM ai1_mcp WHERE deleted = 0) AS mcpCount, " +
            "(SELECT COUNT(1) FROM ai1_model WHERE deleted = 0) AS modelCount")
    Ai1HomeSummaryRespVO selectSummary();

    /**
     * 按天统计消息数量，仅返回有消息的日期；使用 CAST AS DATE 兼容 MySQL、H2 与 PostgreSQL
     */
    @Select("SELECT CAST(create_time AS DATE) AS date, COUNT(1) AS count " +
            "FROM ai1_session_message " +
            "WHERE create_time BETWEEN #{beginTime} AND #{endTime} AND deleted = 0 " +
            "GROUP BY CAST(create_time AS DATE) " +
            "ORDER BY 1")
    List<Ai1HomeMessageSummaryByDateRespVO> selectMessageSummaryListByCreateTimeBetweenGroupByDate(
            @Param("beginTime") LocalDateTime beginTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 按 Agent 统计消息数量，按数量倒序，数量相同按 Agent 编号升序；已删除会话下的消息不计入
     */
    @Select("SELECT c.agent_id AS agentId, COUNT(1) AS count " +
            "FROM ai1_session_message m " +
            "INNER JOIN ai1_session c ON m.session_id = c.id AND c.deleted = 0 " +
            "WHERE m.create_time BETWEEN #{beginTime} AND #{endTime} AND m.deleted = 0 " +
            "GROUP BY c.agent_id " +
            "ORDER BY 2 DESC, c.agent_id")
    List<Ai1HomeMessageSummaryByAgentRespVO> selectMessageSummaryListByCreateTimeBetweenGroupByAgentId(
            @Param("beginTime") LocalDateTime beginTime, @Param("endTime") LocalDateTime endTime);

}
