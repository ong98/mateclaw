package vip.mate.agent.graph.edge;

import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.EdgeAction;
import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.graph.state.MateClawStateAccessor;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 推理路由（分支优先级）
 * <ol>
 *   <li>迭代超限 → limitExceededNode</li>
 *   <li>可直接回答（含 fatal error 自带的错误文案） → finalAnswerNode</li>
 *   <li>LLM 调用次数超限 → limitExceededNode（仅拦截继续循环的路径）</li>
 *   <li>完成门要求继续推理 → reasoningNode</li>
 *   <li>需要工具调用 → actionNode</li>
 *   <li>需要总结压缩 → summarizingNode</li>
 *   <li>兜底 → finalAnswerNode</li>
 * </ol>
 * <p>
 * 注意：ReasoningNode 的 fatal error 路径会自行设置 finalAnswer（错误文案）+
 * finishReason(ERROR_FALLBACK)，因此会命中分支 2 直接走 finalAnswerNode，
 * 不需要也不应该路由到 LimitExceededNode（后者会再发一次 LLM 调用）。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ReasoningDispatcher implements EdgeAction {

    /** LLM 调用次数的安全倍数上限（相对于 maxIterations） */
    private static final int LLM_CALL_MULTIPLIER = 5;

    @Override
    public String apply(RunState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // 1. 迭代超限检查
        if (accessor.isLimitReached()) {
            log.warn("[ReasoningDispatcher] Iteration limit reached ({}/{}), routing to limitExceededNode",
                    accessor.iterationCount(), accessor.maxIterations());
            return LIMIT_EXCEEDED_NODE;
        }

        // 2. 可直接回答 → finalAnswerNode
        //    覆盖以下场景：
        //    - LLM 正常产出最终回答 (needsToolCall=false, finalAnswer 非空)
        //    - fatal error (ReasoningNode 设置了 finalAnswer=错误文案 + finishReason=ERROR_FALLBACK)
        //    - 用户停止无内容 (finalAnswer="" + finishReason=STOPPED)
        //    不受 llm_call_count 限制 — 已有结果不应丢弃。
        if (!accessor.continueReasoning() && !accessor.needsToolCall() && !accessor.shouldSummarize()) {
            log.debug("[ReasoningDispatcher] Routing to finalAnswerNode (direct answer)");
            return FINAL_ANSWER_NODE;
        }

        // 3. LLM 调用次数超限 — 拦截所有继续循环的路径（maxIterations=0 不限制）
        int llmCallCount = accessor.llmCallCount();
        int maxIter = accessor.maxIterations();
        long llmCallLimit = (long) maxIter * LLM_CALL_MULTIPLIER;
        if (maxIter > 0 && llmCallCount >= llmCallLimit) {
            log.warn("[ReasoningDispatcher] LLM call count limit reached ({}/{}), " +
                            "routing to limitExceededNode instead of continuing loop",
                    llmCallCount, llmCallLimit);
            return LIMIT_EXCEEDED_NODE;
        }

        // 4. 完成门要求继续推理
        if (accessor.continueReasoning()) {
            log.info("[ReasoningDispatcher] Completion gate requested another reasoning pass");
            return REASONING_NODE;
        }

        // 5. 工具调用
        if (accessor.needsToolCall()) {
            log.debug("[ReasoningDispatcher] Routing to actionNode (tool call needed)");
            return ACTION_NODE;
        }

        // 6. 需要总结
        if (accessor.shouldSummarize()) {
            log.info("[ReasoningDispatcher] Routing to summarizingNode (observation context too large)");
            return SUMMARIZING_NODE;
        }

        // 7. 兜底
        log.debug("[ReasoningDispatcher] Routing to finalAnswerNode (fallback)");
        return FINAL_ANSWER_NODE;
    }
}
