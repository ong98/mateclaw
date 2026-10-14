package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.progress.*;
import vip.mate.agent.runtime.state.RunState;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeRetryBudgetTest {
    private static NodeStreamingChatHelper.StreamResult blank() {
        return new NodeStreamingChatHelper.StreamResult("","",new AssistantMessage(""),List.of(),false,0,0);
    }
    private static NodeStreamingChatHelper.StreamResult answer() {
        return new NodeStreamingChatHelper.StreamResult("answer","",new AssistantMessage("answer"),List.of(),false,0,0);
    }
    private static NodeStreamingChatHelper.StreamResult ptl() {
        return new NodeStreamingChatHelper.StreamResult(null,null,null,List.of(),false,0,0,false,"provider PTL",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,false,0,0,0);
    }
    private ReasoningNode node(NodeStreamingChatHelper helper, ConversationWindowManager window, int pinChars) {
        return nodeWithModel(helper,window,pinChars,mock(ChatModel.class));
    }
    private ReasoningNode nodeWithModel(NodeStreamingChatHelper helper, ConversationWindowManager window, int pinChars, ChatModel model) {
        var service=mock(ProgressLedgerService.class);
        when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin",new ProgressEntry("pin","skill",ProgressStatus.PENDING,"界".repeat(pinChars),Instant.now()))));
        var node=new ReasoningNode(model,AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,service);
        node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
        return node;
    }
    private List<Message> history() {
        List<Message> messages=new ArrayList<>();messages.add(new UserMessage("ORIGINAL_OBJECTIVE"));
        for(int i=0;i<40;i++)messages.add(new AssistantMessage("OLD_HISTORY_"+"x".repeat(700)));
        return messages;
    }
    private RunState state(List<Message> messages,int max,int consumed) {
        return new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ORIGINAL_OBJECTIVE",MESSAGES,messages,MAX_ITERATIONS,max,LLM_CALL_COUNT,consumed));
    }
    private ConversationWindowManager window() {
        var window=mock(ConversationWindowManager.class);
        when(window.compactAgedToolResponses(anyList(),anyInt())).thenAnswer(inv->inv.getArgument(0));
        when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
        return window;
    }
    @Test void anthropicHighAndOffReserveActualMaxTokensForInitialAndNudgeCalls() throws Exception {
        for(String thinking:List.of("high","off")) {
            var helper=mock(NodeStreamingChatHelper.class);var model=mock(ChatModel.class);
            when(model.getDefaultOptions()).thenReturn(org.springframework.ai.anthropic.AnthropicChatOptions.builder().model("claude-test").maxTokens(1024).build());
            when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(blank(),answer());
            vip.mate.llm.chatmodel.ThinkingLevelHolder.set(thinking);
            try {
                nodeWithModel(helper,null,100,model).apply(state(history(),100,0));
                var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper,times(2)).streamCall(any(),cap.capture(),anyString(),anyString());
                for(Prompt prompt:cap.getAllValues()) {
                    int output=prompt.getOptions().getMaxTokens();
                    assertEquals(thinking.equals("high")?17408:1024,output);
                    assertTrue(TokenEstimator.estimateTokens(prompt.getInstructions())+output<=24000);
                }
            } finally { vip.mate.llm.chatmodel.ThinkingLevelHolder.clear(); }
        }
    }

    @Test void localBudgetNoticeRemainsUnstreamedWhileAlreadyDeliveredThinkingIsPreserved() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("","already streamed thought",new AssistantMessage(""),List.of(),false,0,0));
        var output=node(helper,null,100).apply(state(history(),1,4));
        assertEquals(false,output.get(CONTENT_STREAMED));assertEquals(true,output.get(THINKING_STREAMED));
        var terminal=new FinalAnswerNode().apply(new RunState(output));
        assertEquals(output.get(FINAL_ANSWER),terminal.get(FINAL_ANSWER));assertEquals("incomplete",terminal.get(FINISH_REASON));
    }
    @Test void summaryCancellationSwallowedByCompactorStillStopsWithoutRetry() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();var provider=mock(ChatModel.class);
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl());
        when(provider.call(any(Prompt.class))).thenThrow(new java.util.concurrent.CancellationException("stop"));
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(inv -> {
            try { ((ChatModel)inv.getArgument(1)).call(new Prompt("summary")); }
            catch(java.util.concurrent.CancellationException swallowed) { /* mirrors CWM summary fallback */ }
            return List.of(new UserMessage("ORIGINAL_OBJECTIVE"),new AssistantMessage("fallback"));
        });
        var output=nodeWithModel(helper,window,100,provider).apply(state(history(),100,0));
        verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
        assertEquals(2,output.get(LLM_CALL_COUNT));assertEquals("stopped",output.get(FINISH_REASON));
    }

    @Test void successfulSummaryUsageSurvivesCompactedRetryCancellation() throws Exception { cancelAfterSummary(false); }
    @Test void successfulSummaryUsageSurvivesEmptyNudgeCancellation() throws Exception { cancelAfterSummary(true); }
    private void cancelAfterSummary(boolean emptyRetry) throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();var provider=mock(ChatModel.class);
        var streaming=when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl());
        if (emptyRetry) streaming=streaming.thenReturn(blank());
        streaming.thenThrow(new java.util.concurrent.CancellationException("stop"));
        when(provider.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("summary"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(new org.springframework.ai.chat.metadata.DefaultUsage(19,11,30,null,7L,3L)).build()));
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(inv -> {
            ((ChatModel)inv.getArgument(1)).call(new Prompt("summary"));
            return List.of(new UserMessage("ORIGINAL_OBJECTIVE"),new AssistantMessage("COMPACTED"));
        });
        var output=nodeWithModel(helper,window,100,provider).apply(state(history(),100,0));
        assertEquals("stopped",output.get(FINISH_REASON));assertEquals(emptyRetry?4:3,output.get(LLM_CALL_COUNT));
        assertEquals(19,output.get(PROMPT_TOKENS));assertEquals(11,output.get(COMPLETION_TOKENS));
        assertEquals(7,output.get(CACHE_READ_TOKENS));assertEquals(3,output.get(CACHE_WRITE_TOKENS));
        verify(helper,times(emptyRetry?3:2)).streamCall(any(),any(),anyString(),anyString());
    }
    @Test void localPromptOverflowDoesNotInventProviderCall() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);
        var output=node(helper,null,30000).apply(state(history(),100,7));
        verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
        assertEquals(7,output.get(LLM_CALL_COUNT));assertEquals(false,output.get(CONTENT_STREAMED));
    }
    @Test void summaryPromptOverflowNeverCallsProviderOrInventsSummaryCount() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();var provider=mock(ChatModel.class);
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl());
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(inv -> {
            ((ChatModel)inv.getArgument(1)).call(new Prompt(List.of(new UserMessage("界".repeat(30000))),org.springframework.ai.chat.prompt.ChatOptions.builder().maxTokens(512).build()));
            return List.of(new UserMessage("ORIGINAL_OBJECTIVE"),new AssistantMessage("COMPACTED"));
        });
        var output=nodeWithModel(helper,window,100,provider).apply(state(history(),100,0));
        verify(provider,never()).call(any(Prompt.class));verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
        assertEquals(1,output.get(LLM_CALL_COUNT));assertEquals("error_fallback",output.get(FINISH_REASON));assertEquals(false,output.get(CONTENT_STREAMED));
    }
    @Test void llmCallBudgetUsesLongArithmeticAndUnlimitedCompatibility() {
        assertTrue(ReasoningNode.hasLlmCallBudget(Integer.MAX_VALUE,Integer.MAX_VALUE,2));
        assertTrue(ReasoningNode.hasLlmCallBudget(0,Long.MAX_VALUE,2));
        assertFalse(ReasoningNode.hasLlmCallBudget(1,4,2));
        assertTrue(ReasoningNode.hasLlmCallBudget(1,4,1));
    }

    @Test void actualSummaryCallConsumesBudgetBeforeNestedBlankRetry() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl(),blank(),answer());
        var summaryCalls=new java.util.concurrent.atomic.AtomicInteger();
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(inv -> {
            ((ChatModel)inv.getArgument(1)).call(new Prompt("summary")); summaryCalls.incrementAndGet();
            return List.of(new UserMessage("ORIGINAL_OBJECTIVE"),new AssistantMessage("COMPACTED"));
        });
        var output=node(helper,window,100).apply(state(history(),1,2));
        assertEquals(1,summaryCalls.get());
        verify(helper,times(2)).streamCall(any(),any(),anyString(),anyString());
        assertEquals(5,output.get(LLM_CALL_COUNT));assertEquals("incomplete",output.get(FINISH_REASON));
    }
    @Test void oneRemainingSlotDoesNotStartStructuredSummary() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl());
        var output=node(helper,window,100).apply(state(history(),1,3));
        verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
        verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
        assertEquals(4,output.get(LLM_CALL_COUNT));assertEquals("incomplete",output.get(FINISH_REASON));
    }

    @Test void nearFullWindowEmptyNudgesRemainBoundedAndKeepOriginalObjective() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(blank(),blank(),answer());
        var history=history();
        for(int i=1;i<history.size();i++) history.set(i,new AssistantMessage("x".repeat(700)));
        var original=List.copyOf(history);
        node(helper,null,20000).apply(state(history,100,0));
        var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper,times(3)).streamCall(any(),cap.capture(),anyString(),anyString());
        for(Prompt prompt:cap.getAllValues()) {
            assertTrue(TokenEstimator.estimateTokens(prompt.getInstructions())+1024<=24000);
            assertTrue(prompt.getInstructions().stream().anyMatch(m->m instanceof UserMessage && m.getText().equals("ORIGINAL_OBJECTIVE")));
        }
        assertEquals(original,history);
    }
    @Test void emptyNudgeAfterCompactionUsesActualCompactedHistory() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(ptl(),blank(),answer());
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of(new UserMessage("ORIGINAL_OBJECTIVE"),new AssistantMessage("COMPACTED_HISTORY_MARKER")));
        node(helper,window,15000).apply(state(history(),100,0));
        var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper,times(3)).streamCall(any(),cap.capture(),anyString(),anyString());
        var nudge=cap.getAllValues().get(2);
        assertTrue(nudge.getInstructions().stream().anyMatch(m->m.getText().equals("COMPACTED_HISTORY_MARKER")));
        assertFalse(nudge.getInstructions().stream().anyMatch(m->m.getText().contains("OLD_HISTORY_")));
        assertTrue(TokenEstimator.estimateTokens(nudge.getInstructions())+1024<=24000);
    }
    @Test void lastPermittedInitialCallCannotTriggerBlankRetry() throws Exception { atCap(blank()); }
    @Test void lastPermittedInitialCallCannotTriggerCompactionRetry() throws Exception { atCap(ptl()); }
    private void atCap(NodeStreamingChatHelper.StreamResult initial) throws Exception {
            var helper=mock(NodeStreamingChatHelper.class);var window=window();
            when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(initial);
            var output=node(helper,window,100).apply(state(history(),1,4));
            verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
            verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
            assertEquals(5,output.get(LLM_CALL_COUNT));assertEquals("incomplete",output.get(FINISH_REASON));
    }
    @Test void alreadyExhaustedBudgetNeverCallsModel() throws Exception {
        var helper=mock(NodeStreamingChatHelper.class);var window=window();
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(answer());
        var output=node(helper,window,100).apply(state(history(),1,5));
        verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
        verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
        assertEquals(5,output.get(LLM_CALL_COUNT));assertEquals("incomplete",output.get(FINISH_REASON));
    }
}
