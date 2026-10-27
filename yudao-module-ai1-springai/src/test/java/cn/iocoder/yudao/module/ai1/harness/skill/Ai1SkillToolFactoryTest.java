package cn.iocoder.yudao.module.ai1.harness.skill;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.hutool.core.io.FileUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.ai1.dal.dataobject.agent.Ai1AgentDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.skill.Ai1SkillDO;
import cn.iocoder.yudao.module.ai1.dal.dataobject.skill.Ai1SkillFileDO;
import cn.iocoder.yudao.module.ai1.enums.skill.Ai1SkillFileTypeEnum;
import cn.iocoder.yudao.module.ai1.framework.ai.config.YudaoAi1Properties;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedOutcome;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1GovernedTool;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolGovernor;
import cn.iocoder.yudao.module.ai1.harness.governance.Ai1ToolKind;
import cn.iocoder.yudao.module.ai1.service.skill.Ai1SkillFileService;
import cn.iocoder.yudao.module.ai1.service.skill.Ai1SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * {@link Ai1SkillToolFactory} 的单元测试
 *
 * @author 芋道源码
 */
public class Ai1SkillToolFactoryTest extends BaseMockitoUnitTest {

    private static final Long AGENT_ID = 1L;
    private static final Long SKILL_ID = 10L;
    private static final String SKILL_CONTENT = "---\nname: pdf-reader\ndescription: 读取 PDF\n---\n\n# pdf-reader\n";

    /**
     * The governor's answer, so the test can tell it from anything a SKILL delegate would return.
     *
     * 治理器的回答，用来与任何 SKILL delegate 的返回区分。
     */
    private static final String GOVERNED_MARKER = "GOVERNED-BY-THE-ENGINE";

    @InjectMocks
    private Ai1SkillToolFactory skillToolFactory;

    @Spy
    private YudaoAi1Properties ai1Properties = new YudaoAi1Properties();
    @Mock
    private Ai1SkillService skillService;
    @Mock
    private Ai1SkillFileService skillFileService;

    @TempDir
    private Path tempDir;

    private final List<Ai1GovernedTool> registered = new ArrayList<>();
    private final Ai1ToolGovernor governor = new Ai1ToolGovernor() {
        @Override
        public void registerAll(List<Ai1GovernedTool> tools) {
            registered.addAll(tools);
        }

        @Override
        public Ai1GovernedOutcome execute(String name, Map<String, Object> args, Long caller, Long sessionId) {
            throw new AssertionError("these tests only assemble; nothing should execute");
        }
    };

    @BeforeEach
    public void before() {
        // 设置 SKILL 文件目录
        ai1Properties.getSkill().setRoot(tempDir.toString());
        registered.clear();
    }

    @Test
    public void testBuildTools_materialize() {
        // mock skillService 和 skillFileService 的方法
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo hi");

        // 调用
        List<ToolCallback> tools = skillToolFactory.buildTools(buildAgent(), null, governor);
        // 断言：每个 delegate 都有一个受治理的回调，读写两种性质都在
        assertEquals(registered.size(), tools.size());
        assertTrue(tools.stream().allMatch(ToolCallback.class::isInstance));
        assertTrue(registered.stream().anyMatch(tool -> tool.kind() == Ai1ToolKind.SKILL_READ));
        assertTrue(registered.stream().anyMatch(tool -> tool.kind() == Ai1ToolKind.SKILL_WRITE));
        Path skillDirectory = agentRoot().resolve("pdf-reader");
        assertEquals(SKILL_CONTENT, FileUtil.readUtf8String(skillDirectory.resolve("SKILL.md").toFile()));
        assertEquals("echo hi", FileUtil.readUtf8String(skillDirectory.resolve("scripts/run.sh").toFile()));
        assertTrue(FileUtil.exist(agentRoot().resolve(".fingerprint").toFile()));
    }

    @Test
    public void testBuildTools_fingerprintReuseAndRebuild() {
        // mock skillService 和 skillFileService 的方法
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo v1");
        Ai1AgentDO agent = buildAgent();

        // 调用
        skillToolFactory.buildTools(agent, null, governor);
        skillToolFactory.buildTools(agent, null, governor);
        // 断言
        verify(skillFileService, times(1)).getSkillFileListBySkillId(SKILL_ID);

        // mock 残留文件
        FileUtil.writeUtf8String("stale", agentRoot().resolve("stale.txt").toFile());
        // mock 更新时间和文件内容变化
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 11, 0));
        mockSkillFiles("echo v2");

        // 调用
        skillToolFactory.buildTools(agent, null, governor);
        // 断言
        verify(skillFileService, times(2)).getSkillFileListBySkillId(SKILL_ID);
        assertEquals("echo v2", FileUtil.readUtf8String(agentRoot().resolve("pdf-reader/scripts/run.sh").toFile()));
        assertFalse(FileUtil.exist(agentRoot().resolve("stale.txt").toFile()));
    }

    @Test
    public void testBuildTools_rebuildWhenDirectoryRemoved() {
        // mock skillService 和 skillFileService 的方法
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo hi");
        Ai1AgentDO agent = buildAgent();
        skillToolFactory.buildTools(agent, null, governor);

        // mock 物化目录被删除
        FileUtil.del(agentRoot());

        // 调用
        skillToolFactory.buildTools(agent, null, governor);
        // 断言
        verify(skillFileService, times(2)).getSkillFileListBySkillId(SKILL_ID);
        assertTrue(FileUtil.exist(agentRoot().resolve("pdf-reader/SKILL.md").toFile()));
    }

    @Test
    public void testBuildTools_disabledSkill() {
        // mock skillService 和 skillFileService 的方法
        mockSkill(CommonStatusEnum.DISABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));

        // 调用
        List<ToolCallback> tools = skillToolFactory.buildTools(buildAgent(), null, governor);
        // 断言只保留执行工具，不物化 SKILL 文件；SkillsTool 没有出现
        assertEquals(registered.size(), tools.size());
        assertTrue(registered.stream().noneMatch(tool -> "skills".equalsIgnoreCase(tool.name())));
        assertFalse(FileUtil.exist(agentRoot().resolve("pdf-reader").toFile()));
        verify(skillFileService, never()).getSkillFileListBySkillId(anyLong());
    }

    @Test
    public void testBuildTools_noSkill() {
        // 调用，并断言
        assertTrue(skillToolFactory.buildTools(new Ai1AgentDO().setId(AGENT_ID).setSkillIds(List.of()), null, governor).isEmpty());
        verify(skillService, never()).getSkillList(anyCollection());
    }

    /**
     * A factory with no governor must fail rather than quietly assemble a path that bypasses the engine.
     *
     * 没有治理器的工厂必须失败，而不是悄悄装出一条绕过引擎的路。
     */
    @Test
    public void testBuildTools_noGovernor_fails() {
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo hi");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> skillToolFactory.buildTools(buildAgent(), null, null));
        assertTrue(error.getMessage().contains("must run through governance"));
    }

    /**
     * The callbacks the model receives keep the tool's own name, and every call reaches the governor: the body is
     * the governed execute, not the SKILL's own delegate.
     *
     * 模型收到的回调保留工具自己的名字，而每次调用都先到治理器：执行体是受治理的 execute，不是 SKILL 自己的 delegate。
     */
    @Test
    public void testBuildTools_everyCallReachesTheGovernor() {
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo hi");
        List<String> executed = new ArrayList<>();
        Ai1ToolGovernor recording = new Ai1ToolGovernor() {
            @Override
            public void registerAll(List<Ai1GovernedTool> tools) {
                registered.addAll(tools);
            }

            @Override
            public Ai1GovernedOutcome execute(String name, Map<String, Object> args, Long caller, Long sessionId) {
                executed.add(name);
                return new Ai1GovernedOutcome(ExecutionOutcome.executed(GOVERNED_MARKER, null));
            }
        };

        List<ToolCallback> tools = skillToolFactory.buildTools(buildAgent(), null, recording);
        assertFalse(tools.isEmpty());
        for (ToolCallback tool : tools) {
            // Spring AI serialises the body's String as JSON, so the marker arrives quoted.
            // Spring AI 会把执行体返回的 String 序列化成 JSON，故标记串到达时带引号。
            assertTrue(tool.call("{}").contains(GOVERNED_MARKER),
                    "the model sees the governor's answer, not a delegate's");
        }
        assertEquals(registered.stream().map(Ai1GovernedTool::name).toList(), executed);
    }

    @Test
    public void testEvict() {
        // mock skillService 和 skillFileService 的方法
        mockSkill(CommonStatusEnum.ENABLE.getStatus(), LocalDateTime.of(2026, 10, 1, 10, 0));
        mockSkillFiles("echo hi");
        Ai1AgentDO agent = buildAgent();
        skillToolFactory.buildTools(agent, null, governor);
        assertTrue(FileUtil.exist(agentRoot().toFile()));

        // 调用
        skillToolFactory.evict(AGENT_ID);
        // 断言
        assertFalse(FileUtil.exist(agentRoot().toFile()));

        // 调用
        skillToolFactory.buildTools(agent, null, governor);
        // 断言重新物化
        verify(skillFileService, times(2)).getSkillFileListBySkillId(SKILL_ID);
    }

    // ========== 测试数据 ==========

    private Path agentRoot() {
        return tempDir.resolve("agent_" + AGENT_ID);
    }

    private static Ai1AgentDO buildAgent() {
        return new Ai1AgentDO().setId(AGENT_ID).setSkillIds(List.of(SKILL_ID));
    }

    // ========== mock 方法 ==========

    private void mockSkill(Integer status, LocalDateTime updateTime) {
        Ai1SkillDO skill = new Ai1SkillDO().setId(SKILL_ID).setName("pdf-reader").setStatus(status);
        skill.setUpdateTime(updateTime);
        when(skillService.getSkillList(List.of(SKILL_ID))).thenReturn(List.of(skill));
    }

    private void mockSkillFiles(String scriptContent) {
        Ai1SkillFileDO skillFile = Ai1SkillFileDO.builder().id(100L).skillId(SKILL_ID).parentId(Ai1SkillFileDO.PARENT_ID_ROOT)
                .name(Ai1SkillFileDO.NAME_SKILL).type(Ai1SkillFileTypeEnum.FILE.getType()).content(SKILL_CONTENT).build();
        Ai1SkillFileDO scripts = Ai1SkillFileDO.builder().id(101L).skillId(SKILL_ID).parentId(Ai1SkillFileDO.PARENT_ID_ROOT)
                .name("scripts").type(Ai1SkillFileTypeEnum.DIRECTORY.getType()).build();
        Ai1SkillFileDO script = Ai1SkillFileDO.builder().id(102L).skillId(SKILL_ID).parentId(101L)
                .name("run.sh").type(Ai1SkillFileTypeEnum.FILE.getType()).content(scriptContent).build();
        when(skillFileService.getSkillFileListBySkillId(SKILL_ID)).thenReturn(Arrays.asList(skillFile, scripts, script));
    }

}
