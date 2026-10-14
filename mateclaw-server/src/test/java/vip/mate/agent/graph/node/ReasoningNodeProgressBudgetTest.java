package vip.mate.agent.graph.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.progress.*;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.tool.disclosure.ToolDisclosureService;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
/** 验证进度事实、回执与会话历史在模型上下文预算内保持完整。 */
class ReasoningNodeProgressBudgetTest {
 /** 捕获真实推理及压缩重试请求，正文引用只指向当前请求内仍可见的副本。 */
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void duplicateReadProjectionDoesNotBecomeRetryHistory(boolean bridge) throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var window=mock(ConversationWindowManager.class);
  when(window.compactAgedToolResponses(anyList(),anyInt(),anyString(),nullable(String.class))).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),nullable(String.class))).thenAnswer(inv->inv.getArgument(0));
  String content="1\t相同的原始证据".repeat(500);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  String oldBody=json.writeValueAsString(Map.of("filePath","source.txt","startLine",1,"startColumn",1,"endLine",50,"content",content));
  String newBody=json.writeValueAsString(Map.of("filePath","source.txt","startLine",1,"startColumn",1,"endLine",100,"content",content));
  String protocolName=bridge?"tool_call":"read_file";
  Message oldCall=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("old-read","function",protocolName,"{}"))).build();
  Message newCall=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("new-read","function",protocolName,"{}"))).build();
  var ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("old-read","read_file",oldBody,true),GraphEventPublisher.toolComplete("new-read","read_file",newBody,true)));
  Message oldRead=ToolResponseIdentity.attach(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("old-read",protocolName,oldBody))).build(),ledger);
  Message newRead=ToolResponseIdentity.attach(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("new-read",protocolName,newBody))).build(),ledger);
  List<Message> history=List.of(new UserMessage("ANCHOR"),oldCall,oldRead,newCall,newRead);
  when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of(new UserMessage("ANCHOR"),oldCall,oldRead));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(
    new NodeStreamingChatHelper.StreamResult(null,null,null,List.of(),false,0,0,false,"too long",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG),
    new NodeStreamingChatHelper.StreamResult("done","",new AssistantMessage("done"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,null);
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  var capture=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper,times(2)).streamCall(any(),capture.capture(),anyString(),anyString());
  List<ToolResponseMessage.ToolResponse> first=capture.getAllValues().get(0).getInstructions().stream().filter(m->m instanceof ToolResponseMessage).map(m->(ToolResponseMessage)m).flatMap(m->m.getResponses().stream()).toList();
  assertTrue(first.get(0).responseData().contains("new-read"));
  assertEquals(newBody,first.get(1).responseData());
  List<ToolResponseMessage.ToolResponse> retry=capture.getAllValues().get(1).getInstructions().stream().filter(m->m instanceof ToolResponseMessage).map(m->(ToolResponseMessage)m).flatMap(m->m.getResponses().stream()).toList();
  assertEquals(1,retry.size());assertEquals(oldBody,retry.getFirst().responseData());
 }
 /** 模型请求保留已恢复的规范及数据，避免读取正文很快再次被年龄压缩。 */
 @Test void restoredReadsKeepTheirBodiesWithinActualPromptBudget(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
  var properties=new vip.mate.agent.graph.executor.ToolResultProperties();properties.setStorageBaseDir(root.toString());
  var storage=new vip.mate.agent.graph.executor.ToolResultStorage(properties);
  var window=new ConversationWindowManager(new vip.mate.config.ConversationWindowProperties(),null,null);
  window.setToolResultStorage(storage);
  var helper=mock(NodeStreamingChatHelper.class);
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("result","",new AssistantMessage("result"),List.of(),false,0,0));
  var reader=new vip.mate.tool.builtin.ReadFileTool(mock(vip.mate.i18n.I18nService.class,
          invocation -> "msg".equals(invocation.getMethod().getName()) ? invocation.getArgument(0) : null));
  var events=new ArrayList<GraphEventPublisher.GraphEvent>();var originalCalls=new ArrayList<AssistantMessage.ToolCall>();
  for(String tool:List.of("load_skill","readSkillFile","tool_describe","query_data")) {
   events.add(GraphEventPublisher.toolComplete(tool,tool,"原始证据".repeat(300),true));
   originalCalls.add(new AssistantMessage.ToolCall(tool,"function",tool,"{}"));
  }
  var ledger=ActionExecutionLedger.fromEvents(events).preserveResults(events,originalCalls,storage,"conv",root.toString());
  var history=new ArrayList<Message>();history.add(new UserMessage("ANCHOR"));
  var bodies=new LinkedHashMap<String,String>();
  for(String tool:List.of("load_skill","readSkillFile","tool_describe","query_data")) {
   String id="read-"+tool;String path=ledger.receipts().get(tool).resultRef();
   String body=reader.read_file(path,null,null,null,null);bodies.put(id,body);
   history.add(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","read_file","{}"))).build());
   history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id,"read_file",body))).build());
  }
  for(int i=0;i<4;i++) {
   String id="latest-"+i;
   history.add(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","query_data","{}"))).build());
   history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id,"query_data","recent"))).build());
  }
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,
          MAX_ITERATIONS,100,ACTION_EXECUTION_LEDGER,ledger,WORKSPACE_BASE_PATH,root.toString())));
  var captured=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),captured.capture(),anyString(),anyString());
  var results=new HashMap<String,String>();
  for(Message message:captured.getValue().getInstructions()) if(message instanceof ToolResponseMessage response)
   for(var item:response.getResponses()) results.put(item.id(),item.responseData());
  for(String tool:List.of("load_skill","readSkillFile","tool_describe")) assertEquals(bodies.get("read-"+tool),results.get("read-"+tool));
  assertEquals(bodies.get("read-query_data"),results.get("read-query_data"));
  assertEquals("原始证据".repeat(300),java.nio.file.Files.readString(java.nio.file.Path.of(ledger.receipts().get("query_data").resultRef())));
 }
 /** 压缩后的重试请求也必须满足实际模型窗口限制。 */
 @Test void compressedRetryIsCheckedAgainstActualWindowBeforeProviderCall() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var window=mock(ConversationWindowManager.class);
  when(window.compactAgedToolResponses(anyList(),anyInt(),anyString(),nullable(String.class))).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of(new UserMessage("界".repeat(10000))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(null,null,null,List.of(),false,0,0,false,"provider prompt too long",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,false,0,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,6000,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,List.of(new UserMessage("ANCHOR"),new AssistantMessage("recent")),MAX_ITERATIONS,100)));
  verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
 }
 @Test void actualRemainingWindowBelowTwoThousandStillBoundsNormalHistory() throws Exception {
  assertPinnedWindowFits(20000, 40, 700);
 }
 @Test void fourHugePlainMessagesCannotOverrideRemainingWindow() throws Exception {
  assertPinnedWindowFits(15000, 4, 12000);
 }
 private void assertPinnedWindowFits(int pinChars, int historyCount, int messageChars) throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(pinChars),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHOR"));
  for(int i=0;i<historyCount;i++)history.add(new AssistantMessage("x".repeat(messageChars)));
  var original=List.copyOf(history);
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  assertEquals(original,history);
  assertTrue(cap.getValue().getInstructions().stream().anyMatch(m->m.getText().equals("ANCHOR")));
  assertTrue(TokenEstimator.estimateTokens(cap.getValue().getInstructions())+1024<=24000,
          "captured prompt estimate="+TokenEstimator.estimateTokens(cap.getValue().getInstructions()));
 }
 /** 最新工具调用对无法放入窗口时，不调用模型或尝试无效压缩。 */
 @Test void unfitLatestToolPairReturnsTypedErrorWithoutModelOrCompaction() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var window=mock(ConversationWindowManager.class);
  when(window.compactAgedToolResponses(anyList(),anyInt(),anyString(),nullable(String.class))).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected","",new AssistantMessage("unexpected"),List.of(),false,0,0));
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(15000),Instant.now()))));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=List.of(new UserMessage("ANCHOR"),AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("latest","function","execute_code","x".repeat(16000)))).build(),ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("latest","execute_code","x".repeat(16000)))).build());
  var output=node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
  verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
  assertEquals("error_fallback",output.get(FINISH_REASON));
  assertTrue(output.get(FINAL_ANSWER).toString().contains("工具对"));
 }
 @Test void mandatoryOriginalObjectiveCannotBeSilentlyDroppedToFitWindow() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected","",new AssistantMessage("unexpected"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,4096,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  String objective="界".repeat(5000);
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,objective,MESSAGES,List.of(new UserMessage(objective)),MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
 }
 @Test void completePinsReduceAvailableTailInsteadOfGrowingTotalPromptPastWindow() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(15000),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHOR"));
  for(int i=0;i<40;i++)history.add(new AssistantMessage("x".repeat(700)));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  assertTrue(cap.getValue().getInstructions().stream().anyMatch(m->m.getText().contains("界".repeat(15000))));
  assertTrue(TokenEstimator.estimateTokens(cap.getValue().getInstructions())+1024<=24000);
 }
 /** 完整约束自身超过窗口时立即停止，避免徒劳压缩历史。 */
 @Test void impossibleCompletePinsFailFastWithoutTryingHistoryCompaction() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var window=mock(ConversationWindowManager.class);
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected retry","",new AssistantMessage("unexpected retry"),List.of(),false,0,0));
  when(window.compactAgedToolResponses(anyList(),anyInt(),anyString(),nullable(String.class))).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of());
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(10000),Instant.now()))));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,4096,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"task",MESSAGES,List.of(new UserMessage("task")),MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
  verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
 }
 @Test void actualCapturedPromptBoundsLedgerPreservesReceiptsAnchorPairsAndHistory() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var deferred=mock(ToolCallback.class);
  when(deferred.getToolDefinition()).thenReturn(ToolDefinition.builder().name("big_deferred").description("x".repeat(200000)).inputSchema("{}").build());
  var tools=AgentToolSet.fromCallbacks(List.of(),List.of(deferred));
  var disclosure=mock(ToolDisclosureService.class);
  when(disclosure.split(any(),anySet(),anySet())).thenReturn(new ToolDisclosureService.ToolDisclosureSplit(List.of(),List.of(deferred)));
  var entries=new LinkedHashMap<String,ProgressEntry>();
  for(int i=0;i<1000;i++) entries.put("step"+i,new ProgressEntry("step"+i,"L".repeat(1000),ProgressStatus.DONE,"N".repeat(10000),Instant.now()));
  entries.put("pending",new ProgressEntry("pending","current work",ProgressStatus.PENDING,"keep",Instant.now()));
  when(service.load("conv")).thenReturn(new ProgressLedger(entries));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("result","",new AssistantMessage("result"),List.of(),false,1,1));
  var node=new ReasoningNode(mock(ChatModel.class),tools,null,false,helper,null,null,1024,null,null,disclosure,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.COMPACT,2000,0,0,0,0,600,1000));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHORED_USER_REQUEST"));
  for(int i=0;i<100;i++) {
   String id="id"+i;
   history.add(AssistantMessage.builder().content("thought".repeat(1000)).toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","execute_code","{}"))).build());
   history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id,"execute_code","x".repeat(1500)))).build());
  }
  var original=List.copyOf(history);
  var receipts=new ArrayList<GraphEventPublisher.GraphEvent>();
  for(int i=0;i<24;i++)receipts.add(GraphEventPublisher.toolComplete("r"+i,"execute_code","numeric11984445550"+"z".repeat(480),true));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHORED_USER_REQUEST",MESSAGES,history,MAX_ITERATIONS,100,ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(receipts))));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  var prompt=cap.getValue().getInstructions();
  assertEquals(original,history,"input state history must never be mutated");
  assertTrue(prompt.stream().anyMatch(m->m.getText().equals("ANCHORED_USER_REQUEST")));
  assertTrue(prompt.stream().anyMatch(m->m.getText().contains("11984445550")));
  var snapshot=prompt.stream().filter(m->m.getText().contains("Progress data:")).findFirst().orElseThrow();
  assertTrue(TokenEstimator.estimateTokens(snapshot.getText())<=600);
  assertTrue(snapshot.getText().contains("pending"));assertFalse(snapshot.getText().contains("step999"));
  assertTrue(TokenEstimator.estimateTokens(prompt)+1024<24000);
  assertTrue(prompt.size()<history.size());
  var calls=new HashSet<String>(); var results=new HashSet<String>();
  for(Message m:prompt) {
   if(m instanceof AssistantMessage a) for(var call:a.getToolCalls())calls.add(call.id());
   if(m instanceof ToolResponseMessage t) for(var result:t.getResponses())results.add(result.id());
  }
  assertEquals(calls,results);
 }
}
