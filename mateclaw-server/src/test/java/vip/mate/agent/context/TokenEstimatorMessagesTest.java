package vip.mate.agent.context;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TokenEstimatorMessagesTest {
 @Test void toolResponsesCountBodiesEvenWhenTextIsEmpty() {
  var response=ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id","execute_code","界".repeat(4000)))).build();
  assertEquals("",response.getText());
  assertTrue(TokenEstimator.estimateTokens(response)>4000);
 }
 @Test void assistantToolCallsCountJsonArgumentsEvenWhenContentIsEmpty() {
  var call=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id","function","execute_code","界".repeat(4000)))).build();
  assertEquals("",call.getText());
  assertTrue(TokenEstimator.estimateTokens(call)>4000);
 }
}
