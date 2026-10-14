package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证直接返回工具的元数据和参数保持原样，并禁止图片自动进入视觉模型。
 */
class ReturnDirectMcpToolCallbackTest {

    @Test
    @DisplayName("decorator reports returnDirect=true while delegate stays false")
    void overridesMetadataOnly() {
        ToolCallback delegate = new RecordingDelegate();
        assertFalse(delegate.getToolMetadata().returnDirect(),
                "sanity: bare delegate is not returnDirect");

        ToolCallback wrapped = new ReturnDirectMcpToolCallback(delegate);
        assertTrue(wrapped.getToolMetadata().returnDirect(),
                "decorator must flip returnDirect to true");
        assertEquals(delegate.getToolDefinition().name(), wrapped.getToolDefinition().name(),
                "tool definition name must be delegated unchanged");
        assertEquals(delegate.getToolDefinition().description(), wrapped.getToolDefinition().description(),
                "tool definition description must be delegated unchanged");
    }

    @Test
    @DisplayName("call(args) and call(args, ctx) both delegate")
    /** 两种调用入口均传递图片隔离标记，防止自动媒体分析越过直接返回边界。 */
    void delegatesInvocations() {
        RecordingDelegate delegate = new RecordingDelegate();
        ToolCallback wrapped = new ReturnDirectMcpToolCallback(delegate);

        assertEquals("called-ctx: x", wrapped.call("x"));
        assertEquals(0, delegate.callCount);

        assertEquals("called-ctx: y", wrapped.call("y", null));
        assertEquals(2, delegate.callCtxCount);
    }

    @Test
    @DisplayName("null delegate is rejected at construction time")
    void nullDelegateRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReturnDirectMcpToolCallback(null));
    }

    @Test
    @DisplayName("McpReturnDirectProperties.isReturnDirect matches configured tool names only")
    void propertiesMatchByName() {
        McpReturnDirectProperties props = new McpReturnDirectProperties();
        props.setTools(java.util.Set.of("query_employee_salary", "read_medical_record"));

        assertTrue(props.isReturnDirect("query_employee_salary"));
        assertTrue(props.isReturnDirect("read_medical_record"));
        assertFalse(props.isReturnDirect("get_weather"));
        assertFalse(props.isReturnDirect(null));
        assertFalse(props.isReturnDirect(""));
    }

    private static final class RecordingDelegate implements ToolCallback {
        int callCount;
        int callCtxCount;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("recording_tool")
                    .description("test")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String arguments) {
            callCount++;
            return "called: " + arguments;
        }

        /**
         * 记录上下文调用并校验图片隔离标记。
         * @param arguments 原始工具参数
         * @param toolContext 平台工具上下文
         * @return 用于校验参数透传的文本
         */
        @Override
        public String call(String arguments, ToolContext toolContext) {
            assertEquals(true, toolContext.getContext().get(McpImageStore.SKIP_VISION_CONTEXT_KEY));
            callCtxCount++;
            return "called-ctx: " + arguments;
        }
    }
}
