package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.progress.ProgressEntry;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ProgressLedgerTool#progress_update} — the only mutation entry
 * the LLM has into the conversation-scoped progress ledger. Bad inputs must
 * surface as structured "Error:" strings rather than throwing, so the model
 * can recover by correcting the call instead of breaking the agent run.
 */
class ProgressLedgerToolTest {

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    @Test void completeRecordAndFieldChunksPreserveCanonicalNulls() throws Exception {
        ToolExecutionContext.set("conv", "admin"); var service=mock(ProgressLedgerService.class);
        when(service.load("conv")).thenReturn(new ProgressLedger(Map.of("key",new ProgressEntry("key",null,ProgressStatus.PENDING,null,null))));
        var tool=new ProgressLedgerTool(service); var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var text=mapper.readTree(tool.get_progress("key",null,"text",0,512,null));
        var record=mapper.readTree(text.path("chunk").asText());
        assertTrue(record.path("label").isNull()); assertTrue(record.path("note").isNull()); assertTrue(record.path("updatedAt").isNull());
        var field=mapper.readTree(tool.get_progress("key",null,"note",0,512,null));
        assertTrue(field.path("nullValue").asBoolean()); assertEquals("",field.path("chunk").asText());
    }

    @Test void paginatedFieldChunksReconstructUnicodeControlCharactersAndEntireRecord() throws Exception {
        ToolExecutionContext.set("conv", "admin");
        var service = mock(ProgressLedgerService.class);
        String label = "😀\u0000\n".repeat(1000), note = "instruction-like\n\"error\": bad\u0000".repeat(1000);
        var entry = new ProgressEntry("key",label,ProgressStatus.PENDING,note,Instant.now());
        when(service.load("conv")).thenReturn(new ProgressLedger(Map.of("key",entry)));
        var tool = new ProgressLedgerTool(service); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : java.util.List.of("label","note","text")) {
            StringBuilder recovered = new StringBuilder(); int offset=0;
            do {
                String result = tool.get_progress("key",null,field,offset,512,null);
                assertTrue(result.length()<=2400);
                var page=mapper.readTree(result); recovered.append(page.path("chunk").asText());
                offset=page.path("nextOffset").isNull() ? -1 : page.path("nextOffset").asInt();
            } while(offset>=0);
            if (field.equals("text")) {
                var complete=mapper.readTree(recovered.toString());
                assertEquals(label,complete.path("label").asText()); assertEquals(note,complete.path("note").asText());
                assertEquals(entry.getUpdatedAt().toString(),complete.path("updatedAt").asText());
            } else assertEquals(field.equals("label")?label:note,recovered.toString());
        }
        var receipts=vip.mate.agent.graph.state.ActionExecutionLedger.fromEvents(java.util.List.of(
                vip.mate.agent.GraphEventPublisher.toolComplete("read","get_progress","state",true)));
        assertEquals("",receipts.renderAnswerFacts());
        java.util.Map<String,Object> stats=java.util.Map.of();
        for(int i=0;i<10;i++) {
            var evaluation=vip.mate.agent.graph.guard.ToolLoopGuard.evaluate(stats,java.util.List.of(
                    new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("read","function","get_progress","{}")),java.util.List.of(
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("read","get_progress",tool.get_progress(null,null,null,0,10,null))));
            assertFalse(evaluation.shouldHalt()); stats=evaluation.stats();
        }
        assertEquals(note,entry.getNote());
    }

    @Test void explicitContextWinsAndCollidingOriginsAndUnicodeRemainRecoverable() throws Exception {
        ToolExecutionContext.set("wrong", "admin");
        var service = mock(ProgressLedgerService.class);
        var regular = new LinkedHashMap<String,ProgressEntry>();
        var pinned = new LinkedHashMap<String,ProgressEntry>();
        regular.put("pin_same",new ProgressEntry("pin_same","😀".repeat(100),ProgressStatus.PENDING,"note",Instant.now()));
        pinned.put("pin_same",new ProgressEntry("pin_same","constraint",ProgressStatus.BLOCKED,"complete",Instant.now()));
        when(service.load("right")).thenReturn(new ProgressLedger(regular,pinned));
        var ctx = vip.mate.agent.context.ChatOrigin.EMPTY.withConversationId("right").toToolContext();
        var tool = new ProgressLedgerTool(service); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var overview = mapper.readTree(tool.get_progress(null,null,null,0,10,ctx));
        assertTrue(tool.get_progress(null,1,"text",0,512,ctx).contains("updatedAt"));
        assertEquals(2,overview.path("totalEntries").asInt());assertEquals(1,overview.path("pinnedCount").asInt());
        assertEquals(1,overview.path("counts").path("BLOCKED").asInt());
        assertTrue(tool.get_progress("pin_same",null,"key",0,10,ctx).contains("ambiguous"));
        var chunk=mapper.readTree(tool.get_progress(null,0,"label",0,3,ctx));
        assertEquals("😀",chunk.path("chunk").asText());assertEquals(2,chunk.path("nextOffset").asInt());
        assertTrue(tool.get_progress(null,0,"label",1,3,ctx).contains("surrogate"));
        verify(service,org.mockito.Mockito.never()).load("wrong");
    }

    @Test void escapedOverviewAndChunksAlwaysFitInlineBudget() throws Exception {
        ToolExecutionContext.set("conv", "admin");
        var service = mock(ProgressLedgerService.class);
        var entries = new LinkedHashMap<String,ProgressEntry>();
        for(int i=0;i<20;i++) entries.put("key"+i, new ProgressEntry("key"+i,"\u0000".repeat(100),ProgressStatus.PENDING,"\u0000".repeat(3000),Instant.now()));
        when(service.load("conv")).thenReturn(new ProgressLedger(entries));
        var tool = new ProgressLedgerTool(service);
        String overview = tool.get_progress(null,null,null,0,20,null);
        assertTrue(overview.length() <= 2400, "overview bytes=" + overview.length());
        String chunk = tool.get_progress(null,0,"note",0,512,null);
        assertTrue(chunk.length() <= 2400, "chunk bytes=" + chunk.length());
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunk);
    }

    @Test
    void retrievalIsBoundedReadOnlyAndMissingContextNeverLoads() throws Exception {
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);
        assertTrue(tool.get_progress(null, null, null, 0, 10, null).contains("error"));
        verifyNoInteractions(service);
        ToolExecutionContext.set("conv-1", "admin");
        String key = "huge" + "界".repeat(5000);
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put(key, new ProgressEntry(key, "label", ProgressStatus.PENDING, "n".repeat(3000), Instant.now()));
        when(service.load("conv-1")).thenReturn(new ProgressLedger(entries));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var overview = mapper.readTree(tool.get_progress(null, null, null, 0, 10, null));
        assertEquals(1, overview.path("counts").path("PENDING").asInt());
        assertTrue(overview.path("entries").get(0).path("keyTruncated").asBoolean());
        StringBuilder recovered = new StringBuilder();
        int offset = 0;
        do {
            var chunk = mapper.readTree(tool.get_progress(null, 0, "key", offset, 512, null));
            recovered.append(chunk.path("chunk").asText());
            offset = chunk.path("nextOffset").isNull() ? -1 : chunk.path("nextOffset").asInt();
        } while (offset >= 0);
        assertEquals(key, recovered.toString());
        assertTrue(tool.get_progress(key, null, "note", -1, 10, null).contains("error"));
    }

    @Test
    @DisplayName("Happy path: bounded acknowledgment retains updated entry without repeating full snapshot.")
    void happyPath() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        Map<String, ProgressEntry> after = new LinkedHashMap<>();
        after.put("other", new ProgressEntry("other", "UNRELATED_LONG_ENTRY",
                ProgressStatus.DONE, "x".repeat(2000), Instant.now()));
        after.put("step_a", new ProgressEntry("step_a", "Step A",
                ProgressStatus.IN_PROGRESS, "note", Instant.now()));
        when(service.upsert(eq("conv-1"), eq("step_a"), eq("Step A"),
                eq(ProgressStatus.IN_PROGRESS), eq("starting now")))
                .thenReturn(new ProgressLedger(after));

        ProgressLedgerTool tool = new ProgressLedgerTool(service);
        String out = tool.progress_update("step_a", "Step A", "in_progress", "starting now", null);

        // Header line confirms the write so the model has a clear ack.
        assertTrue(out.startsWith("✓ Recorded step_a → in_progress (2 entries total)"), out);
        // The rendered snapshot must be appended so the model sees its own
        // update reflected in the same view the runtime injects each turn.
        assertFalse(out.contains("当前任务进度"), "full snapshot is injected separately: " + out);
        assertTrue(out.contains("Step A"), "expected entry label in snapshot: " + out);
        assertTrue(out.length() < 300, "acknowledgment should stay bounded: " + out);
        assertFalse(out.contains("UNRELATED_LONG_ENTRY"), out);
        verify(service, times(1)).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void longDisplayKeyIsBoundedWithoutChangingPersistedKey() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        String key = "step_" + "x".repeat(3000);
        when(service.upsert(any(), any(), any(), any(), any())).thenReturn(ProgressLedger.empty());
        String out = new ProgressLedgerTool(service).progress_update(key, "label", "done", null, null);
        assertTrue(out.length() < 300, out);
        verify(service).upsert(eq("conv-1"), eq(key), eq("label"), eq(ProgressStatus.DONE), isNull());
    }

    @Test
    @DisplayName("No conversation context → structured error, no DB call.")
    void missingContext() {
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "done", null, null);
        assertTrue(out.startsWith("Error: no conversation context"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Blank stepKey rejected without touching the service.")
    void blankKey() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("  ", "L", "done", null, null);
        assertTrue(out.startsWith("Error: stepKey is required"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Unknown status string → structured error listing valid values.")
    void unknownStatus() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "finished", null, null);
        assertTrue(out.contains("pending"), out);
        assertTrue(out.contains("in_progress"), out);
        assertTrue(out.contains("done"), out);
        assertTrue(out.contains("blocked"), out);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Service failure surfaces as a structured error instead of throwing.")
    void serviceFailureNotPropagated() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        when(service.upsert(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        String out = tool.progress_update("step_a", "Step A", "done", null, null);
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("disk full"), out);
    }

    @Test
    @DisplayName("Empty 'note' is forwarded verbatim — service decides how to store null vs blank.")
    void emptyNoteForwarded() {
        ToolExecutionContext.set("conv-1", "admin");
        ProgressLedgerService service = mock(ProgressLedgerService.class);
        when(service.upsert(any(), any(), any(), any(), any()))
                .thenReturn(ProgressLedger.empty());
        ProgressLedgerTool tool = new ProgressLedgerTool(service);

        tool.progress_update("step_a", "Step A", "done", null, null);
        verify(service, times(1)).upsert(eq("conv-1"), eq("step_a"), eq("Step A"),
                eq(ProgressStatus.DONE), isNull());
    }
}
