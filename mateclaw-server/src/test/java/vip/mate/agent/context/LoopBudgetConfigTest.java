package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopBudgetConfigTest {
    @Test
    void tinyReportedWindowsProduceValidFloorsRatherThanThrowingBeforeOverflowCheck() {
        for (int window : new int[]{1, 1024, 2048, 4096}) {
            var config = assertDoesNotThrow(() -> LoopBudgetConfig.forContext(window));
            assertTrue(config.keepTailTokens() >= LoopBudgetConfig.DEFAULT_MIN_TAIL_TOKENS);
            assertTrue(config.triggerTokens() > config.keepTailTokens());
        }
    }

    @Test
    void explicitRemainingWindowCanClipBelowDefaultPolicyFloor() {
        var config = LoopBudgetConfig.forContext(32_000).withAvailableHistoryTokens(17);
        assertEquals(17, config.keepTailTokens());
        assertEquals(17, config.tailSoftCeilingTokens());
        assertEquals(2_000, LoopBudgetConfig.DEFAULT_MIN_TAIL_TOKENS);
    }

    @Test
    void ordinaryWindowsKeepTheirExistingRatios() {
        var config = LoopBudgetConfig.forContext(32_000);
        assertEquals(16_000, config.triggerTokens());
        assertEquals(9_600, config.keepTailTokens());
        assertEquals(0, config.reservedPrefixTokens());
    }
}
