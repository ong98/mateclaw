package vip.mate.agent.graph.node;

import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import vip.mate.llm.chatmodel.ChatModelDefaults;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.i18n.I18nService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 超限处理节点
 * <p>
 * 当迭代次数达到 maxIterations 时由 dispatcher 路由至此节点。
 * <b>不会直接抛异常</b>，而是向 LLM 注入友好的系统提示，
 * 要求其基于已有信息给出最终回答，明确标注不确定项。
 * <p>
 * 工程化超限机制：
 * 1. 如果 observationHistory 过长，先做内联压缩
 * 2. 注入 "停止工具调用" 系统指令
 * 3. 让 LLM 生成简洁最终回答
 * 4. 标记 finishReason = MAX_ITERATIONS_REACHED
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class LimitExceededNode implements NodeAction {

    private static final String SYSTEM_TEMPLATE = PromptLoader.loadPrompt("graph/limit-exceeded-system");
    private static final String USER_TEMPLATE = PromptLoader.loadPrompt("graph/limit-exceeded-user");

    private final ChatModel chatModel;
    private final ObservationProcessor observationProcessor;
    private final NodeStreamingChatHelper streamingHelper;
    /** Optional i18n service; nullable so legacy/tests without Spring context still work. */
    private final I18nService i18n;
    /**
     * Optional ledger loader. When set, the conversation's progress snapshot
     * (done / in-progress / pending) is appended to the LLM's context so the
     * "graceful wrap-up" answer can be honest about which steps actually
     * finished and which were still pending when the iteration cap hit.
     * Null in legacy/test constructors — the wrap behaves as before.
     */
    private final vip.mate.agent.progress.ProgressLedgerService progressLedgerService;

    private vip.mate.agent.context.PrefixBudgetPlan prefixBudgetPlan;

    public void setPrefixBudgetPlan(vip.mate.agent.context.PrefixBudgetPlan plan) {
        this.prefixBudgetPlan = plan;
    }

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper) {
        this(chatModel, observationProcessor, streamingHelper, null, null);
    }

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper, I18nService i18n) {
        this(chatModel, observationProcessor, streamingHelper, i18n, null);
    }

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper, I18nService i18n,
                             vip.mate.agent.progress.ProgressLedgerService progressLedgerService) {
        this.chatModel = chatModel;
        this.observationProcessor = observationProcessor;
        this.streamingHelper = streamingHelper;
        this.i18n = i18n;
        this.progressLedgerService = progressLedgerService;
    }

    /**
     * @deprecated use the constructor with {@link NodeStreamingChatHelper} (and optionally {@link I18nService})
     */
    @Deprecated
    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor) {
        this(chatModel, observationProcessor, null, null, null);
    }

    @Override
    public Map<String, Object> apply(RunState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        int maxIterations = accessor.maxIterations();
        String userInput = accessor.userMessage();
        String conversationId = accessor.conversationId();
        List<String> observations = accessor.observationHistory();
        String existingSummary = accessor.summarizedContext();

        log.warn("[LimitExceededNode] Max iterations ({}) reached. Generating graceful final answer. " +
                        "Observations: {} entries, {} chars, existing summary: {} chars",
                maxIterations, observations.size(), accessor.totalObservationChars(),
                existingSummary.length());

        // 准备上下文：优先使用已有 summary，否则压缩 observationHistory
        String contextForLLM;
        if (!existingSummary.isEmpty()) {
            contextForLLM = existingSummary;
        } else if (!observations.isEmpty()) {
            // 内联压缩：拼接观察历史，截断到可控长度
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < observations.size(); i++) {
                sb.append(String.format("【第 %d 轮】%s\n", i + 1, observations.get(i)));
            }
            contextForLLM = observationProcessor.truncate(sb.toString(),
                    observationProcessor.getMaxTotalObservationChars());
        } else {
            contextForLLM = i18n != null ? i18n.msg("agent.limit_exceeded.empty_context") : "(no tool results)";
        }

        // Prepend the conversation's progress ledger snapshot when available
        // so the wrap-up answer can be honest about partial completion ("4/10
        // models researched, 6 still pending") rather than vaguely describing
        // "what I tried". Without this, hitting the iteration cap on a
        // 10-step task produces a useless catch-all message — observed in
        // round-4 of the LLM-review smoke test.
        String ledgerSnapshot = null;
        if (progressLedgerService != null && conversationId != null && !conversationId.isBlank()) {
            try {
                var ledger = progressLedgerService.load(conversationId);
                ledgerSnapshot = prefixBudgetPlan != null && prefixBudgetPlan.enabled()
                        ? ledger.renderSnapshot(prefixBudgetPlan.ledgerTokens()) : ledger.renderSnapshot();
            } catch (Exception e) {
                log.warn("[LimitExceededNode] Failed to load progress ledger for {}: {}",
                        conversationId, e.getMessage());
            }
        }
        if (ledgerSnapshot != null) {
            contextForLLM = ledgerSnapshot + "\n\n---\n\n" + contextForLLM;
        }

        // 构建 prompt
        String systemPrompt = SYSTEM_TEMPLATE.replace("{maxIterations}", String.valueOf(maxIterations));
        String userPrompt = USER_TEMPLATE
                .replace("{question}", userInput)
                .replace("{context}", contextForLLM);

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(systemPrompt));
        String executionFacts = accessor.actionExecutionLedger().renderAnswerFacts();
        if (!executionFacts.isBlank()) promptMessages.add(new SystemMessage(executionFacts));
        promptMessages.add(new UserMessage(userPrompt));

        ChatOptions options = null;
        if (prefixBudgetPlan != null && prefixBudgetPlan.enabled()) {
            ChatOptions defaults = ChatModelDefaults.optionsFor(chatModel);
            int outputReserve = ReasoningNode.outputReserveTokens(defaults,1024);
            boolean declaredOutput = defaults != null && (defaults.getMaxTokens() != null
                    || defaults instanceof org.springframework.ai.openai.OpenAiChatOptions openAi
                    && openAi.getMaxCompletionTokens() != null);
            options = declaredOutput ? defaults.mutate().build()
                    : defaults != null ? defaults.mutate().maxTokens(outputReserve).build()
                    : ChatOptions.builder().maxTokens(outputReserve).build();
            if (options instanceof ToolCallingChatOptions toolOptions) {
                options = toolOptions.mutate().toolCallbacks(List.of()).build();
            }
            int total = TokenEstimator.estimateTokens(promptMessages) + outputReserve;
            if (total > prefixBudgetPlan.effectiveMaxTokens()) {
                log.error("[LimitExceededNode] Wrap-up prompt estimate {} + output {} exceeds window {}",
                        TokenEstimator.estimateTokens(promptMessages),outputReserve,prefixBudgetPlan.effectiveMaxTokens());
                return MateClawStateAccessor.output()
                        .finalAnswerDraft("（必需任务记录与输出预留超过模型上下文窗口，无法安全生成总结。任务尚未完成。）")
                        .llmCallCount(accessor.llmCallCount()).limitExceeded(true)
                        .contentStreamed(false).thinkingStreamed(accessor.thinkingStreamed())
                        .finishReason(FinishReason.INCOMPLETE).build();
            }
        }
        // The graceful wrap retains its separate one-call allowance and advertises no tools.
        Prompt prompt = options == null ? new Prompt(promptMessages) : new Prompt(promptMessages,options);
        NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                chatModel, prompt, conversationId, "limit_exceeded");

        String finalDraft = result.text();

        log.info("[LimitExceededNode] Generated limit-exceeded final answer: {} chars",
                finalDraft != null ? finalDraft.length() : 0);

        String fallbackMsg = i18n != null
                ? i18n.msg("agent.limit_exceeded.fallback")
                : "Sorry, the maximum reasoning steps were reached.";
        return MateClawStateAccessor.output()
                .finalAnswerDraft(finalDraft != null ? finalDraft : fallbackMsg)
                .llmCallCount(accessor.llmCallCount()+1)
                .currentThinking(result.thinking())
                .limitExceeded(true)
                .contentStreamed(true)
                .thinkingStreamed(!result.thinking().isEmpty())
                .mergeUsage(state, result)
                .finishReason(FinishReason.MAX_ITERATIONS_REACHED)
                .build();
    }
}
