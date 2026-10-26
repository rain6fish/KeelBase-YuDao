package cn.iocoder.yudao.module.ai1.harness.skill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.skill.Ai1SkillDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.skill.Ai1SkillFileDO;
import cn.iocoder.yudao.module.ai1.enums.skill.Ai1SkillFileTypeEnum;
import cn.iocoder.yudao.module.ai1.framework.ai.config.YudaoAi1Properties;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolGovernor;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolKind;
import cn.iocoder.yudao.module.ai1.service.skill.Ai1SkillFileService;
import cn.iocoder.yudao.module.ai1.service.skill.Ai1SkillService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * AI1 SKILL 工具工厂（spring-ai-agent-utils SkillsTool）
 *
 * 把 Agent 绑定的 SKILL（数据库中的文件树）物化为本地技能目录，再构建 SkillsTool 与执行类工具：
 * <pre>
 * ${yudao.ai1.skill.root}/
 * └── agent_{agentId}/              # Agent 级沙箱（Agent 编号全局唯一，无需按租户分层）
 *     ├── .fingerprint              # 指纹标记，仅用于本地缓存校验
 *     └── {skillName}/              # 每个 SKILL 一个子目录
 *         ├── SKILL.md
 *         ├── scripts/
 *         └── reference/
 * </pre>
 * 变更检测：SKILL 内容变更会刷新其 update_time，这里按「编号:更新时间」计算指纹，不一致时整目录重建
 * （先清空再物化，杜绝换绑、删除后的残留文件）
 *
 * 注意：执行类工具（Shell、FileSystem 等）会在服务器上执行命令，与源工程行为一致，多租户不可信部署需自行评估
 *
 * @author 芋道源码
 */
@Component
@Slf4j
public class Ai1SkillToolFactory {

    /**
     * 指纹标记文件名
     */
    private static final String FILE_NAME_FINGERPRINT = ".fingerprint";

    /**
     * 快照缓存的最大数量
     */
    private static final int SNAPSHOT_CACHE_MAX_SIZE = 1000;

    @Resource
    private YudaoAi1Properties ai1Properties;

    @Resource
    private Ai1SkillService skillService;
    @Resource
    private Ai1SkillFileService skillFileService;

    /**
     * Agent 物化快照缓存：Agent 编号 → 快照
     *
     * 集群下各节点独立缓存，每次使用前按「编号:更新时间」指纹校验，Agent 换绑或 SKILL 变更后自动重建，无需跨节点失效
     */
    private final Cache<Long, SkillSnapshot> snapshotCache = CacheBuilder.newBuilder()
            .maximumSize(SNAPSHOT_CACHE_MAX_SIZE).build();
    /**
     * 按 Agent 串行物化的锁，避免并发重建互相破坏目录
     */
    private final Map<Long, Object> syncLocks = new ConcurrentHashMap<>();

    /**
     * 构建 Agent 绑定 SKILL 所需的全部工具，并让它们**经治理引擎**执行。
     *
     * <p>工具本身与它们的沙箱（Agent 物化根目录）不变；变的是每次调用的落点：模型看到的仍是 SKILL 自己声明的
     * 名字、描述与入参 schema，而调用先到宿主注册的治理工具——只读的 Glob/Grep/ListDirectory 走 R1，shell、
     * 文件与 Skill 走 R2 且**默认禁用**，管理员在策略中心放开。执行体按名字从 {@link Ai1SkillExecutionContext}
     * 取**这一轮、这个 agent** 的 delegate，故同一个工具名在不同 agent 的沙箱里各跑各的。
     *
     * <p>没有治理器时**明确失败**：悄悄退回一条不经引擎的路，正是这次改动要消灭的形态。
     *
     * Build the tools this agent's SKILLs need, and run them **through the governance engine**.
     *
     * <p>The tools and their sandbox (the agent's materialised root) do not change; where a call lands
     * does. The model still sees the name, description and input schema the SKILL declares, and the
     * call first reaches the host's governed tool — Glob/Grep/ListDirectory at R1, shell/file/Skill at
     * R2 and **disabled by default** until an administrator enables them in the Policy Center. The body
     * resolves this turn's, this agent's delegate by name from {@link Ai1SkillExecutionContext}, so one
     * tool name runs in each agent's own sandbox.
     *
     * <p>No governor is a **loud failure**: quietly falling back to a path that bypasses the engine is
     * the shape this change removes.
     */
    public List<ToolCallback> buildTools(Ai1AgentDO agent, Long sessionId, Ai1ToolGovernor governor) {
        // 1. 同步物化快照；未绑定有效 SKILL 时，不提供任何工具
        SkillSnapshot snapshot = sync(agent);
        if (snapshot == null) {
            return Collections.emptyList();
        }
        if (governor == null) {
            throw new IllegalStateException("no Ai1ToolGovernor — SKILL tools must run through governance");
        }

        // 2. 把 SkillsTool 与执行类工具统一成 ToolCallback，并读出它们自己的声明
        List<ToolCallback> raw = new ArrayList<>();
        if (snapshot.getSkillsTool() != null) {
            raw.add(snapshot.getSkillsTool());
        }
        Path root = snapshot.getRoot();
        for (Object object : List.of(
                ShellTools.builder().workingDirectory(root).build(),
                FileSystemTools.builder().allowedDirectory(root).build(),
                GlobTool.builder().workingDirectory(root).allowedDirectory(root).build(),
                GrepTool.builder().workingDirectory(root).allowedDirectory(root).build(),
                ListDirectoryTool.builder().workingDirectory(root).allowedDirectory(root).build())) {
            Collections.addAll(raw, ToolCallbacks.from(object));
        }

        // 3. 每个工具描述一次；执行体按名字取这一轮的 delegate
        Map<String, ToolCallback> delegates = new LinkedHashMap<>();
        List<Ai1GovernedTool> specs = new ArrayList<>();
        for (ToolCallback callback : raw) {
            ToolDefinition definition = callback.getToolDefinition();
            delegates.put(definition.name(), callback);
            specs.add(new Ai1GovernedTool(
                    definition.name(),
                    definition.description(),
                    readOnlyTool(definition.name()) ? Ai1ToolKind.SKILL_READ : Ai1ToolKind.SKILL_WRITE,
                    definition.inputSchema(),
                    arguments -> delegateCall(definition.name(), arguments)));
        }
        governor.registerAll(specs);

        // 4. 模型看到的是原来的名字/描述/schema；调用先到引擎
        Long caller = SecurityFrameworkUtils.getLoginUserId();
        List<ToolCallback> governed = new ArrayList<>();
        for (ToolCallback callback : raw) {
            ToolDefinition definition = callback.getToolDefinition();
            String name = definition.name();
            Function<Map<String, Object>, String> call = arguments ->
                    withCaller(caller, () -> Ai1SkillExecutionContext.run(delegates,
                            () -> governor.execute(name, arguments, caller, sessionId).forModel()));
            governed.add(FunctionToolCallback.builder(name, call)
                    .description(definition.description())
                    .inputType(Map.class)
                    .inputSchema(definition.inputSchema())
                    .build());
        }
        return governed;
    }

    /** The body of a governed SKILL tool: this turn's delegate, by name. / 受治理 SKILL 工具的执行体。 */
    private static Object delegateCall(String name, Map<String, Object> arguments) {
        ToolCallback delegate = Ai1SkillExecutionContext.current(name);
        if (delegate == null) {
            throw new IllegalStateException("no SKILL delegate named `" + name + "` in this turn");
        }
        return delegate.call(JsonUtils.toJsonString(arguments == null ? Map.of() : arguments));
    }

    /**
     * Which SKILL tools change nothing: Glob, Grep and ListDirectory. Everything else — shell, the file
     * tools (including Read), and SkillsTool — starts behind the write gate.
     *
     * 哪些 SKILL 工具不改动任何东西：Glob、Grep、ListDirectory。其余——shell、文件工具（含 Read）与
     * SkillsTool——都从写闸后面起步。
     */
    private static boolean readOnlyTool(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "glob".equals(normalized) || "grep".equals(normalized)
                || "listdirectory".equals(normalized) || "list_directory".equals(normalized);
    }

    /** Run the body with the submitting caller in the security context. / 在投递它的调用方的安全上下文里跑。 */
    private static String withCaller(Long userId, Supplier<String> body) {
        if (userId == null) {
            return body.get();
        }
        LoginUser caller = new LoginUser();
        caller.setId(userId);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(caller, null, List.of()));
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 清理 Agent 物化沙箱：摘除快照缓存并删除本地目录（Agent 删除时调用）
     *
     * @param agentId Agent 编号
     */
    public void evict(Long agentId) {
        snapshotCache.invalidate(agentId);
        try {
            FileUtil.del(buildAgentRoot(agentId));
        } catch (Exception e) {
            log.warn("[evict][Agent({}) SKILL 沙箱清理失败]", agentId, e);
        }
    }

    /**
     * 同步并返回 Agent 物化快照：指纹变化则整目录重建，否则复用缓存
     */
    private SkillSnapshot sync(Ai1AgentDO agent) {
        // 1.1 查询绑定的 SKILL
        if (CollUtil.isEmpty(agent.getSkillIds())) {
            return null;
        }
        List<Ai1SkillDO> skills = skillService.getSkillList(agent.getSkillIds());
        if (CollUtil.isEmpty(skills)) {
            return null;
        }
        // 1.2 指纹一致时，直接复用缓存
        String fingerprint = buildFingerprint(skills);
        SkillSnapshot cached = snapshotCache.getIfPresent(agent.getId());
        if (isSnapshotValid(cached, fingerprint)) {
            return cached;
        }

        // 2. 指纹变化，整目录重建（双重检查，并发请求下避免重复重建）
        synchronized (syncLocks.computeIfAbsent(agent.getId(), key -> new Object())) {
            cached = snapshotCache.getIfPresent(agent.getId());
            if (isSnapshotValid(cached, fingerprint)) {
                return cached;
            }
            Path root = buildAgentRoot(agent.getId());
            try {
                // 2.1 清空目录后，逐个物化开启的 SKILL
                FileUtil.del(root);
                FileUtil.mkdir(root.toFile());
                boolean hasSkill = false;
                for (Ai1SkillDO skill : skills) {
                    if (CommonStatusEnum.isEnable(skill.getStatus())
                            && materializeSkill(root.resolve(sanitize(skill.getName())), skill.getId())) {
                        hasSkill = true;
                    }
                }
                // 2.2 写入指纹标记，构建技能工具并缓存快照
                FileUtil.writeUtf8String(fingerprint, root.resolve(FILE_NAME_FINGERPRINT).toFile());
                ToolCallback skillsTool = hasSkill ? SkillsTool.builder().addSkillsDirectory(root.toString()).build() : null;
                SkillSnapshot snapshot = new SkillSnapshot(fingerprint, root, skillsTool);
                snapshotCache.put(agent.getId(), snapshot);
                log.info("[sync][Agent({}) SKILL 物化完成，目录({})]", agent.getId(), root);
                return snapshot;
            } catch (Exception e) {
                log.warn("[sync][Agent({}) SKILL 物化失败]", agent.getId(), e);
                return cached;
            }
        }
    }

    /**
     * 计算 SKILL 指纹：按编号排序后拼接「编号:更新时间」，任一 SKILL 换绑或内容变更都会导致指纹变化
     *
     * @param skills SKILL 列表
     * @return 指纹
     */
    private static String buildFingerprint(List<Ai1SkillDO> skills) {
        return skills.stream().sorted(Comparator.comparing(Ai1SkillDO::getId))
                .map(skill -> skill.getId() + ":" + skill.getUpdateTime())
                .collect(Collectors.joining(";"));
    }

    /**
     * 本地快照是否有效：指纹一致，且标记文件存在、内容一致（防止目录被外部清理）
     */
    private static boolean isSnapshotValid(SkillSnapshot cached, String fingerprint) {
        // 1. 内存快照不存在，或指纹不一致
        if (cached == null || ObjUtil.notEqual(fingerprint, cached.getFingerprint())) {
            return false;
        }
        // 2. 标记文件不存在或内容不一致，说明目录已被外部清理或篡改
        File marker = cached.getRoot().resolve(FILE_NAME_FINGERPRINT).toFile();
        return FileUtil.exist(marker) && fingerprint.equals(FileUtil.readUtf8String(marker));
    }

    /**
     * 物化单个 SKILL 的文件树到技能目录
     *
     * @return 是否包含根级 SKILL.md
     */
    private boolean materializeSkill(Path skillDirectory, Long skillId) {
        // 1. 查询 SKILL 的全部节点，构建编号映射，用于沿 parentId 解析相对路径
        List<Ai1SkillFileDO> files = skillFileService.getSkillFileListBySkillId(skillId);
        Map<Long, Ai1SkillFileDO> fileMap = convertMap(files, Ai1SkillFileDO::getId);

        // 2. 逐个节点写入：文件写入内容（自动创建父目录），目录直接创建
        Map<Long, Path> pathCache = new HashMap<>();
        boolean hasSkillFile = false;
        for (Ai1SkillFileDO file : files) {
            File target = skillDirectory.resolve(resolveRelativePath(file, fileMap, pathCache)).toFile();
            if (Ai1SkillFileTypeEnum.isFile(file.getType())) {
                // 根级的 SKILL.md 是技能入口
                hasSkillFile |= Ai1SkillFileDO.NAME_SKILL.equals(file.getName())
                        && ObjUtil.equal(file.getParentId(), Ai1SkillFileDO.PARENT_ID_ROOT);
                FileUtil.writeUtf8String(StrUtil.nullToEmpty(file.getContent()), target);
            } else {
                FileUtil.mkdir(target);
            }
        }

        // 3. 缺少根级 SKILL.md 时，只物化文件，不作为技能注册
        if (!hasSkillFile) {
            log.warn("[materializeSkill][SKILL({}) 缺少根级 SKILL.md]", skillId);
        }
        return hasSkillFile;
    }

    /**
     * 解析节点相对路径：沿 parentId 链自底向上拼接，每段名称净化以防目录穿越
     */
    private static Path resolveRelativePath(Ai1SkillFileDO file, Map<Long, Ai1SkillFileDO> fileMap, Map<Long, Path> pathCache) {
        Path cached = pathCache.get(file.getId());
        if (cached != null) {
            return cached;
        }
        Path segment = Path.of(sanitize(file.getName()));
        Ai1SkillFileDO parent = fileMap.get(file.getParentId());
        // 父节点为自身时（异常数据）按根级处理，避免无限递归
        Path path = parent == null || ObjUtil.equal(parent.getId(), file.getId())
                ? segment : resolveRelativePath(parent, fileMap, pathCache).resolve(segment);
        pathCache.put(file.getId(), path);
        return path;
    }

    private Path buildAgentRoot(Long agentId) {
        return Path.of(ai1Properties.getSkill().getRoot(), "agent_" + agentId);
    }

    /**
     * 路径净化：屏蔽路径分隔符与穿越片段
     */
    private static String sanitize(String name) {
        if (StrUtil.isBlank(name)) {
            return "_";
        }
        return StrUtil.replace(StrUtil.replaceChars(name, new char[]{'\\', '/'}, "_"), "..", "_");
    }

    /**
     * Agent 物化快照
     */
    @Data
    @AllArgsConstructor
    private static class SkillSnapshot {

        /**
         * 指纹
         */
        private final String fingerprint;
        /**
         * Agent 物化根目录
         */
        private final Path root;
        /**
         * 技能工具；无有效 SKILL.md 时为空
         */
        private final ToolCallback skillsTool;

    }

}
