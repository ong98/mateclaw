package vip.mate.tool.mcp.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 将 MCP 工具标记为直接返回用户，并禁止图片处理把结果额外发送到视觉模型。
 *
 * <p>Spring AI 1.1.4's {@code SyncMcpToolCallback} / {@code AsyncMcpToolCallback}
 * never override {@code getToolMetadata()} (they inherit the framework default
 * which yields {@code returnDirect=false}), and the upstream MCP protocol layer
 * has no equivalent field. So MateClaw must wrap MCP callbacks at registration
 * time when their server+tool config opts in via
 * {@code mateclaw.mcp.return-direct.tools}.
 *
 * <p>工具定义、参数和异常继续透传；仅在本地调用上下文添加图片隔离标记。
 *
 * @author MateClaw Team
 */
public final class ReturnDirectMcpToolCallback implements ToolCallback {

    private static final ToolMetadata RETURN_DIRECT_METADATA =
            ToolMetadata.builder().returnDirect(true).build();

    private final ToolCallback delegate;

    public ReturnDirectMcpToolCallback(ToolCallback delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return RETURN_DIRECT_METADATA;
    }

    /**
     * 使用带图片隔离标记的上下文执行直接返回调用。
     * @param arguments 原始工具参数
     * @return 直接交给用户的工具内容
     */
    @Override
    public String call(String arguments) {
        return call(arguments, null);
    }

    /**
     * 直接返回工具内容，禁止媒体解析路径将数据额外发送给视觉模型。
     * @param arguments 原始工具参数
     * @param toolContext 当前工具执行上下文
     * @return 直接交给用户的工具内容
     */
    @Override
    public String call(String arguments, ToolContext toolContext) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        if (toolContext != null) values.putAll(toolContext.getContext());
        values.put(McpImageStore.SKIP_VISION_CONTEXT_KEY, true);
        return delegate.call(arguments, new ToolContext(values));
    }

    /** Test/diagnostic accessor — not part of the framework contract. */
    public ToolCallback getDelegate() {
        return delegate;
    }
}
