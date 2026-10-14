package vip.mate.agent.graph.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.PrefixBudgetPlan;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.progress.*;
import vip.mate.agent.runtime.state.RunState;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
class LimitExceededNodeProgressTest {
 @Test void oversizedCompletePinsDoNotCallProviderOrSuppressLocalIncompleteNotice() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin",new ProgressEntry("pin","skill",ProgressStatus.PENDING,"界".repeat(30000),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected","",new AssistantMessage("unexpected"),List.of(),false,0,0));
  var node=new LimitExceededNode(mock(ChatModel.class),null,helper,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  var output=node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",USER_MESSAGE,"task",MAX_ITERATIONS,4,LLM_CALL_COUNT,20)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
  assertEquals(20,output.get(LLM_CALL_COUNT));assertEquals("incomplete",output.get(FINISH_REASON));assertEquals(false,output.get(CONTENT_STREAMED));
 }
 @Test void permittedWrapAccountsActualOutputReserveAndAdvertisesNoTools() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);var model=mock(ChatModel.class);
  var tool=mock(org.springframework.ai.tool.ToolCallback.class);
  when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder().name("hidden").description("x".repeat(100000)).inputSchema("{}").build());
  when(model.getDefaultOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().maxTokens(1024).toolCallbacks(List.of(tool)).build());
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin",new ProgressEntry("pin","skill",ProgressStatus.PENDING,"界".repeat(15000),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new LimitExceededNode(model,null,helper,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  var output=node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",USER_MESSAGE,"task",MAX_ITERATIONS,4,LLM_CALL_COUNT,20)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)cap.getValue().getOptions();
  assertTrue(options.getToolCallbacks().isEmpty());
  assertTrue(TokenEstimator.estimateTokens(cap.getValue().getInstructions())+options.getMaxTokens()<=24000);
  assertEquals(21,output.get(LLM_CALL_COUNT));
 }
 @Test void completionOnlyWrapPreservesActualSdkOutputField() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var model=mock(ChatModel.class);
  when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(1024).build());
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new LimitExceededNode(model,null,helper,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(USER_MESSAGE,"task",MAX_ITERATIONS,4)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  var options=(org.springframework.ai.openai.OpenAiChatOptions)cap.getValue().getOptions();
  assertNull(options.getMaxTokens());assertEquals(1024,options.getMaxCompletionTokens());
  when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().maxCompletionTokens(30000).build());
  clearInvocations(helper);
  var output=node.apply(new RunState(Map.of(USER_MESSAGE,"task",MAX_ITERATIONS,4)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());assertEquals("incomplete",output.get(FINISH_REASON));
 }
 @Test void wrapUsesBoundedLedgerAndAuthoritativeExecutionFacts() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class); var service=mock(ProgressLedgerService.class);
  var entries=new LinkedHashMap<String,ProgressEntry>();
  for(int i=0;i<100;i++) entries.put("s"+i,new ProgressEntry("s"+i,"huge".repeat(1000),ProgressStatus.DONE,"n".repeat(10000),Instant.now()));
  when(service.load("conv")).thenReturn(new ProgressLedger(entries));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new LimitExceededNode(mock(ChatModel.class),null,helper,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,32000,PrefixBudgetPlan.Profile.NORMAL,2000,0,0,0,0,400,0));
  var ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("failed","execute_code","failed value",false),GraphEventPublisher.toolComplete("ok","execute_code","11984445550",true)));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",USER_MESSAGE,"task",MAX_ITERATIONS,4,ACTION_EXECUTION_LEDGER,ledger)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  String prompt=cap.getValue().getInstructions().toString();
  assertTrue(prompt.contains("failedCallCount"));assertTrue(prompt.contains("11984445550"));assertTrue(prompt.contains("omitted"));
  assertFalse(prompt.contains("s99"));assertTrue(TokenEstimator.estimateTokens(prompt)<3000);
 }
}
