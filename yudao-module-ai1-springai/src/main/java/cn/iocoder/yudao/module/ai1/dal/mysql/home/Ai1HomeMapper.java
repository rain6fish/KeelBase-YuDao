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

    // `deleted` is `smallint` on PostgreSQL (upstream's PG conversion), so it compares to `0`, not to
    // `FALSE` — `deleted = FALSE` is `smallint = boolean` there and PostgreSQL has no such operator.
    // `deleted` 在 PostgreSQL 上是 `smallint`（上游那份 PG 转换如此），故它和 `0` 比、不和 `FALSE` 比 ——
    // 在那边 `deleted = FALSE` 是 `smallint = boolean`，PostgreSQL 没有这个运算符。
    @Select("SELECT (SELECT COUNT(1) FROM ai1_agent WHERE deleted = 0) AS agentCount, " +
            "(SELECT COUNT(1) FROM ai1_skill WHERE deleted = 0) AS skillCount, " +
            "(SELECT COUNT(1) FROM ai1_mcp WHERE deleted = 0) AS mcpCount, " +
            "(SELECT COUNT(1) FROM ai1_model WHERE deleted = 0) AS modelCount")
    Ai1HomeSummaryRespVO selectSummary();

    /**
     * Message counts per day, only days that have messages. The aliases are unquoted so one statement
     * serves MySQL, H2 and PostgreSQL: a backtick is a syntax error on PostgreSQL, and a double-quoted
     * alias is a string literal on MySQL. MyBatis matches result columns to properties
     * case-insensitively, so the camel-case aliases work even where the server folds them to lowercase.
     *
     * 按天统计消息数量，仅返回有消息的日期。别名不加引号，故同一条语句在 MySQL、H2 与 PostgreSQL 上都成立：
     * 反引号在 PostgreSQL 上是语法错，双引号别名在 MySQL 上会被当成字符串字面量。MyBatis 比对结果列与属性时
     * 不区分大小写，故即使服务器把别名折成小写，驼峰别名照样能映射上。
     */
    @Select("SELECT CAST(create_time AS DATE) AS date, COUNT(1) AS count " +
            "FROM ai1_session_message " +
            "WHERE create_time BETWEEN #{beginTime} AND #{endTime} AND deleted = 0 " +
            "GROUP BY CAST(create_time AS DATE) " +
            "ORDER BY date")
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
            "ORDER BY count DESC, c.agent_id")
    List<Ai1HomeMessageSummaryByAgentRespVO> selectMessageSummaryListByCreateTimeBetweenGroupByAgentId(
            @Param("beginTime") LocalDateTime beginTime, @Param("endTime") LocalDateTime endTime);

}
