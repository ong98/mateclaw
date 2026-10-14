package vip.mate.agent.graph.node;

import vip.mate.llm.chatmodel.ChatModelDefaults;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import vip.mate.llm.dashscope.NativeDashScopeChatOptions;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ObservationSummaryInput;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static vip.mate.agent.graph.state.MateClawStateKeys.OBSERVATION_HISTORY;

/**
 * 总结压缩节点（Summarizing 阶段）
 * <p>
 * 当满足以下条件之一时由 dispatcher 路由至此节点：
 * <ul>
 *   <li>最后一轮不再需要工具调用，但 observationHistory 过长</li>
 *   <li>单次工具结果超过阈值</li>
 *   <li>多轮观察已经足够回答，但直接传给 FinalAnswerNode 过于冗长</li>
 * </ul>
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class SummarizingNode implements NodeAction {

    private static final String SYSTEM_PROMPT = PromptLoader.loadPrompt("graph/summarize-system");
    private static final String USER_TEMPLATE = PromptLoader.loadPrompt("graph/summarize-user");

    private final ChatModel chatModel;
    private final NodeStreamingChatHelper streamingHelper;
    private final ChatStreamTracker streamTracker;

    /** 摘要观察原文复用已有存储，未配置或归档失败时保护正文。 */
    private ToolResultStorage toolResultStorage;
    /** 从原进度台账提取当前步骤，进度文字不作为执行成功证明。 */
    private ProgressLedgerService progressLedgerService;
    /** 包含摘要系统提示及工作集的独立输入预算。 */
    private int inputMaxTokens = 12000;

    /**
     * 配置摘要输入预算和恢复依赖，复用原执行台账及文件存储。
     * @param storage 当前原文存储
     * @param progressService 当前进度台账服务
     * @param maxTokens 摘要输入预算，原目标与未归档正文不因预算丢失
     */
    public void configureInput(ToolResultStorage storage, ProgressLedgerService progressService, int maxTokens) {
        this.toolResultStorage = storage;
        this.progressLedgerService = progressService;
        this.inputMaxTokens = Math.max(1, maxTokens);
    }

    public SummarizingNode(ChatModel chatModel, NodeStreamingChatHelper streamingHelper, ChatStreamTracker streamTracker) {
        this.chatModel = chatModel;
        this.streamingHelper = streamingHelper;
        this.streamTracker = streamTracker;
    }

    public SummarizingNode(ChatModel chatModel, NodeStreamingChatHelper streamingHelper) {
        this(chatModel, streamingHelper, null);
    }

    /**
     * @deprecated Use constructor with NodeStreamingChatHelper
     */
    @Deprecated
    public SummarizingNode(ChatModel chatModel) {
        this(chatModel, null, null);
    }

    /**
     * 在独立输入预算内生成交接摘要，原观察可恢复，真实事实及当前目标优先保留。
     * @param state 当前执行事实、目标与观察历史
     * @return 摘要及使用量状态增量，原推理历史不在此裁剪
     * @throws Exception 用户停止或摘要调用无法完成
     */
    @Override
    public Map<String, Object> apply(RunState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // 取消检查
        String cid = accessor.conversationId();
        if (streamTracker != null && streamTracker.isStopRequested(cid)) {
            log.info("[SummarizingNode] Stop requested, aborting");
            throw new CancellationException("Stream stopped by user");
        }

        String userInput = accessor.userMessage();
        String conversationId = accessor.conversationId();
        List<String> observations = accessor.observationHistory();

        log.info("[SummarizingNode] Summarizing {} observations ({} total chars) for user query",
                observations.size(), accessor.totalObservationChars());
        pushPhase(conversationId, "summarizing_observations", Map.of(
                "observationCount", observations.size(),
                "summaryChars", accessor.totalObservationChars()
        ));

        String workingSet = "";
        if (progressLedgerService != null) {
            try {
                workingSet = progressLedgerService.load(conversationId).renderSnapshot(2000);
            } catch (RuntimeException unavailable) {
                log.warn("[SummarizingNode] progress unavailable for conv={}: {}", conversationId, unavailable.getMessage());
            }
        }
        String facts = accessor.actionExecutionLedger().renderAnswerFacts(java.util.Set.of(), workingSet);
        String handoff = "\n[当前任务工作集；进度文字不是执行证明]\n" + workingSet + "\n" + facts;
        if (!accessor.summarizedContext().isBlank()) {
            handoff += "\n[上一阶段摘要；以本轮真实回执为准]\n" + accessor.summarizedContext();
        }
        String workspace = state.value(vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH, "");
        if (vip.mate.workspace.core.service.MemberFileIsolation.isEnabled()) {
            var scoped = vip.mate.workspace.core.service.MemberFileIsolation.scope(accessor.chatOrigin());
            workspace = scoped == null ? null : scoped.workspaceBasePath();
        }
        String skeleton = USER_TEMPLATE.replace("{question}", userInput).replace("{observations}", handoff);
        int reserved = TokenEstimator.estimateTokens(SYSTEM_PROMPT) + TokenEstimator.estimateTokens(skeleton) + 16;
        var input = ObservationSummaryInput.prepare(observations, toolResultStorage, conversationId,
                "summary-" + accessor.chatOrigin().originMessageId() + "-" + accessor.iterationCount()
                        + "-" + accessor.toolCallCount(), workspace,
                Math.max(0, inputMaxTokens - reserved));
        log.info("[SummarizingNode] input_budget: conversationId={} originalChars={} observationTokens={} "
                        + "reservedTokens={} inputMaxTokens={} archiveFailed={}", conversationId, input.originalChars(),
                TokenEstimator.estimateTokens(input.text()), reserved, inputMaxTokens, input.archiveFailed());

        String userPrompt = USER_TEMPLATE
                .replace("{question}", userInput)
                .replace("{observations}", handoff + "\n" + input.text());

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        promptMessages.add(new UserMessage(userPrompt));

        // Summarization is mechanical text compression — disable thinking/reasoning to avoid
        // inheriting the user's thinkingLevel=high from the model's default options.
        // Without this override, a plain Prompt would inherit extended thinking from chatModel
        // defaults, causing 100+ second delays for a task that needs no deep reasoning.
        Prompt summarizePrompt = buildNoThinkingPrompt(promptMessages);

        // 流式调用 LLM，实时推送 content/thinking
        NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                chatModel, summarizePrompt, conversationId, "summarizing");

        // 摘要失败时保留目标、执行事实及可恢复观察，不再丢弃为前 500 字符。
        if (result.hasFatalError()) {
            log.warn("[SummarizingNode] Summarization LLM call failed: {}, using raw observations as fallback",
                    result.errorMessage());
            String fallback = "[原始目标]\n" + userInput + handoff + "\n" + input.text();
            AssistantMessage fallbackMsg = new AssistantMessage("[工具观察摘要(降级)]\n" + fallback);
            return MateClawStateAccessor.output()
                    .summarizedContext(fallback)
                    .shouldSummarize(false)
                    .put(OBSERVATION_HISTORY, List.of())
                    .messages(List.of((Message) fallbackMsg))
                    .contentStreamed(true)
                    .thinkingStreamed(true)
                    .mergeUsage(state, result)
                    .events(List.of(GraphEventPublisher.phase("summarize_fallback", Map.of(
                            "error", result.errorMessage() != null ? result.errorMessage() : "unknown"))))
                    .build();
        }
        // 用户主动停止：将已生成的部分摘要写入 state 作为 finalAnswer + finalThinking
        if (result.stopped()) {
            String partialText = result.text() != null ? result.text() : "";
            String partialThinking = result.thinking() != null ? result.thinking() : "";
            log.info("[SummarizingNode] Stop requested with partial summary ({} chars, thinking {} chars), " +
                            "flushing to state before cancellation",
                    partialText.length(), partialThinking.length());
            var builder = MateClawStateAccessor.output()
                    .summarizedContext(partialText)
                    .shouldSummarize(false)
                    .put(OBSERVATION_HISTORY, List.of())
                    .messages(List.of())
                    .finalAnswer(partialText)
                    .contentStreamed(true)
                    .mergeUsage(state, result)
                    .finishReason(FinishReason.STOPPED);
            if (!partialThinking.isEmpty()) {
                builder.finalThinking(partialThinking);
                builder.thinkingStreamed(true);
            }
            return builder.build();
        }
        if (result.partial()) {
            log.warn("[SummarizingNode] Partial summarization result, using available content");
        }

        String summarized = result.text();

        log.info("[SummarizingNode] Generated summarized context: {} chars, " +
                        "clearing observation history and injecting into messages for next reasoning iteration",
                summarized != null ? summarized.length() : 0);

        // 将摘要注入 messages，让下一轮 ReasoningNode 能看到之前的工具调用结论
        String summaryContent = summarized != null ? summarized : "";
        if (input.sourcePath() != null) {
            summaryContent += "\n[完整观察快照] " + input.sourcePath()
                    + "\n摘要有损；省略内容按需只读恢复，不重放历史工具。";
        }
        AssistantMessage summaryMessage = new AssistantMessage(
                "[工具观察摘要]\n" + summaryContent);

        return MateClawStateAccessor.output()
                .summarizedContext(summaryContent)
                .shouldSummarize(false)
                // 清空观察历史（REPLACE 策略），防止下一轮立刻再次触发 summarize
                .put(OBSERVATION_HISTORY, List.of())
                // 注入摘要消息，让 ReasoningNode 的 LLM 继续推理
                .messages(List.of((Message) summaryMessage))
                .currentThinking(result.thinking())
                // 摘要的 content 已流式推送，但它不是最终回答，标记防重即可
                .contentStreamed(true)
                .thinkingStreamed(!result.thinking().isEmpty())
                // 把当轮 summary 文本写入 STREAMED_CONTENT，让 StateGraphReActAgent 用 persistOnly
                // StreamDelta 推给 Accumulator 持久化（用户刷新页面后能看到摘要正文，否则只剩 tool_call 卡片）
                .streamedContent(summaryContent)
                .mergeUsage(state, result)
                // 不设 finishReason — summarizing 不是终止，循环继续
                .events(List.of(GraphEventPublisher.phase("summarized", Map.of(
                        "observationCount", observations.size(),
                        "summaryChars", summaryContent.length()))))
                .build();
    }

    /**
     * Build a Prompt with thinking/reasoning explicitly disabled.
     * Summarization is mechanical compression — it never needs extended reasoning,
     * and inheriting the user's thinkingLevel=high from model defaults wastes 100+ seconds.
     */
    private Prompt buildNoThinkingPrompt(List<Message> messages) {
        ChatOptions opts;
        if (ChatModelDefaults.optionsFor(chatModel) instanceof AnthropicChatOptions defaults) {
            opts = defaults.mutate().thinkingDisabled().toolCallbacks(List.of()).build();
        } else {
            // OpenAI / DashScope / other: omit reasoningEffort to disable chain-of-thought
            if (ChatModelDefaults.optionsFor(chatModel) instanceof OpenAiChatOptions defaults) {
                opts = defaults.mutate().reasoningEffort(null).streamUsage(true).toolCallbacks(List.of()).build();
            } else if (ChatModelDefaults.optionsFor(chatModel) instanceof NativeDashScopeChatOptions defaults) {
                opts = defaults.mutate().enableThinking(false).toolCallbacks(List.of()).build();
            } else {
                ChatOptions defaults = ChatModelDefaults.optionsFor(chatModel);
                if (defaults instanceof ToolCallingChatOptions tools) {
                    opts = tools.mutate().toolCallbacks(List.of()).build();
                } else {
                    opts = defaults != null ? defaults.mutate().build() : ChatOptions.builder().build();
                }
            }
        }
        return new Prompt(messages, opts);
    }

    private void pushPhase(String conversationId, String phase, Map<String, Object> extra) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        streamTracker.updatePhase(conversationId, phase);
        streamTracker.broadcastObject(conversationId, "phase", GraphEventPublisher.phase(phase, extra).data());
    }
}
