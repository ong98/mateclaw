package vip.mate.agent.binding.service;

import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.repository.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证技能绑定保留默认图片分析能力，同时尊重员工关闭工具的显式选择。 */
class AgentBindingImageAnalyzeTest {

    /**
     * 创建已有技能绑定且没有原子工具绑定的员工场景。
     * @param toolsDisabled 是否显式关闭工具
     * @return 已隔离数据库依赖的绑定服务
     */
    private AgentBindingService service(boolean toolsDisabled) {
        AgentMapper agents = mock(AgentMapper.class);
        AgentEntity agent = new AgentEntity();
        agent.setToolsDisabled(toolsDisabled);
        when(agents.selectById(1L)).thenReturn(agent);
        AgentBindingService service = spy(new AgentBindingService(mock(AgentSkillBindingMapper.class),
                mock(AgentToolBindingMapper.class), mock(AgentProviderPreferenceMapper.class),
                mock(AgentWikiKbBindingMapper.class), mock(WikiKnowledgeBaseMapper.class),
                mock(SkillRuntimeService.class), mock(AvailableToolService.class), agents,
                mock(SkillMapper.class), mock(AcpSkillBridge.class)));
        doReturn(Set.of(2L)).when(service).getBoundSkillIds(1L);
        doReturn(toolsDisabled ? Set.of() : null).when(service).getBoundToolNames(1L);
        return service;
    }

    /** 技能未声明图片分析时，通用白名单仍保留此能力。 */
    @Test
    void skillBindingKeepsDefaultImageAnalyze() {
        assertTrue(service(false).getEffectiveToolNames(1L).contains("image_analyze"));
    }

    /** 默认开放列表不能覆盖员工主动关闭工具的选择。 */
    @Test
    void explicitToolOptOutDoesNotRestoreImageAnalyze() {
        assertFalse(service(true).getEffectiveToolNames(1L).contains("image_analyze"));
    }
}
