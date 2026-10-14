package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.runtime.AgentRuntime;
import vip.mate.agent.runtime.state.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class StateGraphReActAgentInitialBudgetTest {
    private static final class Agent extends StateGraphReActAgent {
        private final List<Message> history;
        Agent(AgentRuntime runtime,ChatModel model,ConversationWindowManager window,List<Message> history) {
            super(null,null,runtime,model,window);this.history=history;
            this.agentId="1";this.systemPrompt="system";this.maxIterations=1;this.maxInputTokens=24000;
        }
        void legacyWindow() { this.maxInputTokens=null; }
        @Override protected List<Message> buildConversationHistory(String conversationId,String userMessage) { return history; }
        @Override protected BaseAgent.CurrentTurnUserMessage buildCurrentUserMessageWithRouting(String conversationId,String userMessage) {
            return new BaseAgent.CurrentTurnUserMessage(new UserMessage(userMessage),null);
        }
    }
    private List<Message> history() {
        return List.of(new UserMessage("old"),AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id","function","execute_code","{}"))).build(),ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id","execute_code","old result"))).build());
    }
    private org.mockito.stubbing.OngoingStubbing<List<Message>> fit(ConversationWindowManager window) {
        return when(window.fitToWindow(anyList(),anyString(),anyString(),any(),any(),anyString(),any(),any(),nullable(String.class)));
    }
    @Test void realSetupSummaryCallsAndUsageReachInitialState() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))),ChatResponseMetadata.builder().usage(new DefaultUsage(19,11,30,null,7L,3L)).build()));
        fit(window).thenAnswer(inv -> { ((ChatModel)inv.getArgument(4)).call(new Prompt("summary"));return inv.getArgument(0); });
        when(runtime.invoke(anyMap())).thenAnswer(inv -> Optional.of(new RunState(inv.<Map<String,Object>>getArgument(0))));
        var original=history();var agent=new Agent(runtime,model,window,original);
        agent.chat("CURRENT_USER","conv");
        var cap=org.mockito.ArgumentCaptor.forClass(Map.class);verify(runtime).invoke(cap.capture());
        var inputs=cap.getValue();assertEquals(1,inputs.get(LLM_CALL_COUNT));assertEquals(19,inputs.get(PROMPT_TOKENS));assertEquals(11,inputs.get(COMPLETION_TOKENS));assertEquals(7,inputs.get(CACHE_READ_TOKENS));assertEquals(3,inputs.get(CACHE_WRITE_TOKENS));
        var messages=(List<Message>)inputs.get(MESSAGES);assertEquals("CURRENT_USER",messages.getLast().getText());assertEquals(original,messages.subList(0,original.size()));
    }
    @Test void knownOversizedSetupSummaryNeverCallsProviderOrBusinessRuntimeAndStreamsIncomplete() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        fit(window).thenAnswer(inv -> {
            try { ((ChatModel)inv.getArgument(4)).call(new Prompt(List.of(new UserMessage("界".repeat(30000))),ChatOptions.builder().maxTokens(512).build())); }
            catch(RuntimeException swallowed) { /* mirrors CWM fallback */ }
            return inv.getArgument(0);
        });
        when(runtime.stream(anyMap())).thenReturn(Flux.empty());
        var deltas=new Agent(runtime,model,window,history()).chatStructuredStream("CURRENT_USER","conv").collectList().block();
        verify(model,never()).call(any(Prompt.class));verify(runtime,never()).stream(anyMap());
        assertTrue(deltas.stream().anyMatch(d->d.content()!=null && d.content().contains("未完成") && !d.persistenceOnly()));
        assertTrue(deltas.stream().anyMatch(d->"finish_reason".equals(d.eventType()) && "incomplete".equals(d.eventData().get("reason"))));
    }
    @Test void legacyNullableWindowIsPassedToCompactorWithoutUnboxing() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        fit(window).thenAnswer(inv -> { assertNull(inv.getArgument(3));return inv.getArgument(0); });
        when(runtime.invoke(anyMap())).thenAnswer(inv -> Optional.of(new RunState(inv.<Map<String,Object>>getArgument(0))));
        var agent=new Agent(runtime,model,window,history());agent.legacyWindow();
        agent.chat("CURRENT_USER","conv");verify(runtime).invoke(anyMap());
    }
    @Test void actualEnabledWindowIsPassedToPreRunCompactionPlanner() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        fit(window).thenAnswer(inv -> { assertEquals(16000,inv.<Integer>getArgument(3));return inv.getArgument(0); });
        when(runtime.invoke(anyMap())).thenAnswer(inv -> Optional.of(new RunState(inv.<Map<String,Object>>getArgument(0))));
        var agent=new Agent(runtime,model,window,history());
        agent.setPrefixBudgetPlan(new vip.mate.agent.context.PrefixBudgetPlan(true,16000,vip.mate.agent.context.PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
        agent.chat("CURRENT_USER","conv");verify(runtime).invoke(anyMap());
    }
    @Test void completionOnlySummaryDefaultsAreGuardedBeforeInitialStateRuntime() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(30000).build());
        fit(window).thenAnswer(inv -> {
            try { ((ChatModel)inv.getArgument(4)).call(new Prompt("summary")); } catch(RuntimeException swallowed) {}
            return inv.getArgument(0);
        });
        var output=new Agent(runtime,model,window,history()).chat("CURRENT_USER","conv");
        assertTrue(output.contains("未完成"));verify(model,never()).call(any(Prompt.class));verify(runtime,never()).invoke(anyMap());
    }
    @Test void swallowedSetupCancellationStopsBeforeBusinessRuntime() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        when(model.call(any(Prompt.class))).thenThrow(new CancellationException("stop"));
        fit(window).thenAnswer(inv -> {
            try { ((ChatModel)inv.getArgument(4)).call(new Prompt("summary")); } catch(CancellationException swallowed) {}
            return inv.getArgument(0);
        });
        when(runtime.stream(anyMap())).thenReturn(Flux.empty());
        var deltas=new Agent(runtime,model,window,history()).chatStructuredStream("CURRENT_USER","conv").collectList().block();
        verify(model,times(1)).call(any(Prompt.class));verify(runtime,never()).stream(anyMap());
        assertTrue(deltas.stream().anyMatch(d->"finish_reason".equals(d.eventType()) && "stopped".equals(d.eventData().get("reason"))));
    }
    @Test void setupReservesBusinessCallAndCountsOnlyActualSummaryRequests() {
        var model=mock(ChatModel.class);var window=mock(ConversationWindowManager.class);var runtime=mock(AgentRuntime.class);
        fit(window).thenAnswer(inv -> {
            for(int i=0;i<5;i++) { try { ((ChatModel)inv.getArgument(4)).call(new Prompt("summary")); } catch(RuntimeException swallowed) {} }
            return inv.getArgument(0);
        });
        when(runtime.stream(anyMap())).thenReturn(Flux.empty());
        new Agent(runtime,model,window,history()).chatStructuredStream("CURRENT_USER","conv").collectList().block();
        verify(model,times(4)).call(any(Prompt.class));verify(runtime,never()).stream(anyMap());
    }
}
