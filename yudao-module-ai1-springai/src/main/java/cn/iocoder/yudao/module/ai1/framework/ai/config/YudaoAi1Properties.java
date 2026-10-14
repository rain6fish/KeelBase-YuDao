package cn.iocoder.yudao.module.ai1.framework.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 芋道 AI1 配置类
 *
 * @author 芋道源码
 */
@ConfigurationProperties(prefix = "yudao.ai1")
@Data
public class YudaoAi1Properties {

    /**
     * pgvector 向量库：用宿主已有的 DataSource，不再需要 Milvus 容器
     *
     * pgvector vector store: it uses the host's own DataSource, so no Milvus container is needed
     */
    private Pgvector pgvector = new Pgvector();

    /**
     * 会话流
     */
    private Session session = new Session();

    /**
     * SKILL 技能
     */
    private Skill skill = new Skill();

    @Data
    public static class Pgvector {

        /**
         * 知识库向量表名前缀，实际表名为 {tablePrefix}{知识库编号}
         *
         * Prefix of a knowledge base's vector table; the table is {tablePrefix}{knowledgeBaseId}
         */
        private String tablePrefix = "knowledge_base_";
        /**
         * 默认检索数量，知识库未配置检索数量时使用
         *
         * Default number of chunks retrieved, used when a knowledge base sets none of its own
         */
        private Integer defaultTopK = 5;

    }

    @Data
    public static class Session {

        /**
         * 默认系统指令中的 Agent 名称占位符
         */
        public static final String AGENT_NAME_PLACEHOLDER = "{agentName}";

        /**
         * 默认系统指令：Agent 未配置系统指令时使用，{agentName} 占位符替换为 Agent 名称
         */
        private String defaultSystemPrompt = "你是 " + AGENT_NAME_PLACEHOLDER + " 的智能助手。";
        /**
         * 结果流
         */
        private Stream stream = new Stream();
        /**
         * 生成 worker
         */
        private Worker worker = new Worker();
        /**
         * SSE 连接
         */
        private Sse sse = new Sse();
        /**
         * 上下文历史
         */
        private History history = new History();

    }

    @Data
    public static class Stream {

        /**
         * 单次生成 / 单 SSE 连接最长时长，单位：毫秒
         */
        private Long timeoutMs = 180000L;
        /**
         * 结果流保留时长，单位：秒；决定断线、刷新后可续传的时间窗
         */
        private Long ttlSeconds = 3600L;

    }

    @Data
    public static class Worker {

        /**
         * 单节点生成 worker 线程数（多实例部署时为每个实例的数量）
         */
        private Integer count = 4;

    }

    @Data
    public static class Sse {

        /**
         * 单节点 SSE 最大并发连接数，超出时拒绝新连接
         */
        private Integer maxConnections = 64;

    }

    @Data
    public static class History {

        /**
         * 附加给模型的最近历史消息条数上限
         */
        private Integer limit = 20;

    }

    @Data
    public static class Skill {

        /**
         * 技能物化根目录，实际目录为 {root}/agent_{agentId}（Agent 编号全局唯一，无需按租户分层）；
         * 默认放在用户目录下，集群部署建议挂载共享持久卷
         */
        private String root = System.getProperty("user.home") + "/yudao-ai1/skills";

    }

}
