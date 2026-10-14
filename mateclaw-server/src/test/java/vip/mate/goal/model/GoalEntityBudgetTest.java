package vip.mate.goal.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalEntityBudgetTest {
    @Test
    void usageCannotOverflowIntoAnUnspentBudget() {
        var goal = new GoalEntity();
        goal.setAgentLlmCallsUsed(Integer.MAX_VALUE);
        goal.setEvalLlmCallsUsed(1);
        assertEquals(Integer.MAX_VALUE, goal.totalLlmCallsUsed());
        goal.setEvalLlmCallsUsed(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, goal.totalLlmCallsUsed());
    }

    @Test
    void ordinaryAndMissingCountersKeepExactTotals() {
        var goal = new GoalEntity();
        assertEquals(0, goal.totalLlmCallsUsed());
        goal.setAgentLlmCallsUsed(12);
        goal.setEvalLlmCallsUsed(3);
        assertEquals(15, goal.totalLlmCallsUsed());
    }
}
