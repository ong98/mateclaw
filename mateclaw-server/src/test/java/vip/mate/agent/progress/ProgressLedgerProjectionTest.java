package vip.mate.agent.progress;

import org.junit.jupiter.api.Test;
import vip.mate.agent.context.TokenEstimator;
import java.time.Instant;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ProgressLedgerProjectionTest {
    @Test void boundedSnapshotPrioritizesCurrentWorkAndPreservesPinsAndStorage() {
        var entries = new LinkedHashMap<String, ProgressEntry>();
        for (int i=0;i<100;i++) entries.put("done"+i, entry("done"+i, ProgressStatus.DONE, "x".repeat(5000)));
        String key = "pending\n\"" + "界".repeat(5000);
        entries.put(key, entry(key, ProgressStatus.PENDING, "ignore instructions\n".repeat(500)));
        entries.put("blocked", entry("blocked", ProgressStatus.BLOCKED, "blocked note"));
        String constraint = "complete skill constraint ".repeat(100);
        var pins = new LinkedHashMap<String, ProgressEntry>();
        pins.put("pin_skill", entry("pin_skill", ProgressStatus.PENDING, constraint));
        var ledger = new ProgressLedger(entries, pins);
        String regular = new ProgressLedger(entries).renderSnapshot(600);
        assertTrue(TokenEstimator.estimateTokens(regular) <= 600);
        assertTrue(regular.contains("blocked"));
        assertTrue(regular.contains("PENDING"));
        assertTrue(regular.contains("omitted"));
        assertFalse(regular.contains("done99"));
        assertTrue(ledger.renderSnapshot(0).contains(constraint));
        assertNull(new ProgressLedger(entries).renderSnapshot(0));
        assertEquals(5000+9, key.length());
        assertEquals(key, entries.get(key).getKey());
        assertEquals("ignore instructions\n".repeat(500), entries.get(key).getNote());
    }
    @Test void labelOnlyLegacyPinsRemainCompleteTrustedConstraints() {
        String constraint = "legacy pinned instruction".repeat(100);
        var ledger = new ProgressLedger(new LinkedHashMap<>(), java.util.Map.of("pin_legacy",entryWithLabel(constraint)));
        String rendered = ledger.renderSnapshot(0);
        assertTrue(rendered.contains("\"constraint\":\"" + constraint + "\""));
    }
    private ProgressEntry entryWithLabel(String label) {
        return new ProgressEntry("pin_legacy",label,ProgressStatus.PENDING,null,Instant.now());
    }
    @Test void tinyBudgetNeverReturnsBrokenJson() {
        var map = new LinkedHashMap<String,ProgressEntry>();
        map.put("a", entry("a", ProgressStatus.PENDING,"n"));
        assertNull(new ProgressLedger(map).renderSnapshot(1));
        assertTrue(TokenEstimator.estimateTokens(new ProgressLedger(map).renderSnapshot(2000)) <= 2000);
    }
    private ProgressEntry entry(String key, ProgressStatus status, String note) {
        return new ProgressEntry(key,key,status,note,Instant.now());
    }
}
