# Structured decision layer

The decision layer provides replaceable structured judgments. It does not execute tools or replace permissions, completion verification, or existing state machines. Requests use Choice, Boolean, or Score questions. Domain constraints remain authoritative after a Provider returns a suggestion.

## Modes and configuration

`mate.decision.mode` defaults to `SHADOW`:

| Mode | Behavior |
|---|---|
| OFF | Returns the legacy judgment without calling the new Provider or decision record store |
| SHADOW | Keeps the legacy judgment; a bounded background queue evaluates and records suggestions |
| ACTIVE | Uses suggestions that pass hard constraints, type checks, and confidence checks; domain services still commit state |

The following staged-rollout example observes only Goal decisions. Without scenario overrides, all three scenarios inherit the global SHADOW mode.

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    provider-threads: 4
    shadow-threads: 2
    queue-capacity: 64
    retention-days: 30
    retention-batch-size: 500
    retention-interval-ms: 3600000
    scenarios:
      GOAL_CONTINUATION: SHADOW
      WORKER_RESULT: OFF
      AGENT_ROUTING: OFF
```

Scenario settings override the global mode. To disable the layer completely, set the global mode to OFF and remove or disable scenario overrides. The built-in `rule` Provider evaluates deterministic scheduling rules for supported Goal v2 inputs. Other requests return the baseline without a confidence value. The `llm` Provider remains an unavailable stub; these rules do not call a new model service.

## Processing

1. A deterministic guard checks hard constraints and can bypass the Provider.
2. The Provider produces a typed suggestion within a bounded executor and deadline.
3. Invalid types or candidates, timeout, failure, abstention, or low confidence fall back to the legacy judgment.
4. A policy override applies domain policy without bypassing the guard.
5. The legacy value, Provider suggestion, and effective suggestion are stored separately from the actual committed outcome.

SHADOW always returns the legacy judgment. Queue saturation or background recording failure reduces observation coverage. ACTIVE persists a proposal before returning it; actual outcomes participate in the caller's business transaction. A persisted proposal does not prove that business state committed.

## Integration boundaries

- Goal continuation preserves completion evidence, budgets and leases. An ACTIVE suggestion can conservatively defer or retry an otherwise eligible continuation.
- Worker judging preserves deterministic invalid-result checks, checkpoint rules and approval. An ACTIVE semantic rejection fails the task through the existing state machine; it does not grant another execution attempt. Settlement rechecks the original owner, dispatch count and conversation under lock.
- Agent routing is limited to non-Team plan steps; Team Lead decisions and explicit channel or tool routing remain outside this layer.

## Observability and privacy

Records use `mate_decision_record` and `mate_decision_outcome`. Metadata includes scenario, mode, Provider/version, policy/question versions, workspace and execution identifiers, pipeline evaluation duration (excluding later business commits), confidence, fallback cause, and override cause. Stored values are structured; raw prompts, answers, SQL, tool arguments, evidence text, and exception messages are excluded.

| Outcome | Meaning |
|---|---|
| PENDING | A proposal exists without a confirmed outcome; inferred from a missing outcome row |
| OBSERVED | The actual legacy-path result was observed |
| APPLIED | The caller recorded an actual result at an explicit application boundary |
| NOT_APPLIED | Concurrency checks, limits, or other constraints prevented application |

Graph-local `FOLLOWUP_SUBMITTED` means a followup output was constructed and submitted. `FOLLOWUP_SUPPRESSED` means an ACTIVE DEFER/RETRY suggestion prevented the current graph followup; it does not schedule a durable retry. These outcomes do not prove subsequent nodes executed or provide an atomic database-and-graph commit guarantee.

Micrometer metrics include `mate.decision.total`, `mate.decision.duration`, `mate.decision.comparison`, and `mate.decision.failure`. Business identifiers are excluded from metric tags. Comparison metrics distinguish raw Provider suggestions from effective suggestions; guards and unavailable Providers are not valid model comparisons.

Routing v2 records correlate by parent Agent, conversation and PLAN_STEP phase. Step index is transient and plan/subplan IDs are not persisted in the audit, so exact audit-to-subplan reconstruction is not available. Actual assignment outcomes still commit with plan insertion.

Queries must be scoped to workspace and time range. No new public query API is provided. Agreement rates must be considered alongside dropped work, failures, and missing outcomes. Agreement from the Rule provider does not establish improved model quality.

## Validation and future Providers

Tests use controlled Providers and do not require real models. Database tests use H2 with MySQL/PostgreSQL compatibility modes; these do not replace native MySQL/Kingbase validation.

Future Providers can implement the Java SPI for rules, remote models, or local inference. Rollout requires fixed protocol/model versions, minimized input, domain evaluation including Chinese, confidence calibration, timeout/circuit-breaking policies, and gradual enablement by scenario. Language capability metadata does not currently implement language detection or calibration. Industrial writes, uncertain side effects, and retry idempotency remain governed by existing execution and approval mechanisms.

## Scenario policies and routing inputs (second iteration)

Keep mode overrides in `scenarios`. Optional `scenario-policies` overrides provider, confidence threshold and timeout independently; omitted fields inherit globals, so existing configuration needs no migration. This example still uses the built-in Rule provider:

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    scenario-policies:
      WORKER_RESULT:
        confidence-threshold: 0.95
        timeout-ms: 500
      AGENT_ROUTING:
        provider: rule
        timeout-ms: 150
```

Overrides are validated at startup: timeouts are 1–60000 ms and thresholds are finite numbers in [0,1]. Unknown providers fall back with UNAVAILABLE rather than selecting the global provider. Each request snapshots its policy before SHADOW queuing. Dynamic configuration refresh is not provided. Example thresholds are not calibrated production recommendations; Rule baseline results do not use confidence thresholds.

Routing v2 supplies the current step as transient evidence and eligible same-workspace candidates' and parent's name, description, tags and agent type as option descriptions. It excludes the full conversation, full original goal, system prompts, runtime configuration and model credentials. The original goal is only inspected locally for explicit Agent selection. Descriptions are unverified capability claims, not tool authorization. Missing parent metadata uses a generic LOCAL description.

Limits: 2048 characters per step, 1024 per option description, and 8192 across step and option descriptions. Missing steps or excessive inputs guard the baseline instead of silently truncating meaning; stale candidates retain the existing LOCAL guard priority. Character limits are not tokenizer limits. Audit records contain typed results and question versions, never these transient texts. Future remote providers still require an outbound-data policy for transient inputs.

This iteration adds no candidate retrieval, live tool capability verification or semantic model provider. Agreement under default SHADOW/Rule does not establish better accuracy or execution efficiency.

## Goal facts and conservative scheduling (third iteration)

`GoalDecisionFacts` snapshots immutable `goal-continuation-v2` inputs at submission. Boolean and numeric facts cover Goal status, automatic followups, persistence, turns, business and evaluator model calls, finite or unlimited budgets, criterion counts, and passed criteria with evidence. Call totals use long arithmetic so int overflow cannot invalidate a budget. Goal, criterion, and evidence text are excluded from the snapshot and decision audit.

Criterion JSON must satisfy a field whitelist, unique IDs, and type checks. Duplicate JSON fields, trailing content, inputs over 65536 characters or 1000 criteria, and other malformed inputs are marked invalid. An actual evaluation requires a nonblank evaluator model and positive call usage; score, decision/completed consistency, and revision are validated. A valid synthetic CONTINUE with no model and zero calls is scheduling input, not a fresh acceptance verdict. Partial provenance is invalid. Full revision comparisons distinguish current from stale evaluations.

Cooldown facts use the same scheduling time supplied by the adapter. With a known nonnegative configuration, the last followup determines active/ready; readiness includes the exact deadline. Missing configuration/time or date overflow remains unknown and falls back to the baseline. The compatible two-argument snapshot never invents facts from the system clock. Existing domain cooldown guards and the graph planning snapshot remain unchanged; timestamps are excluded from decision facts.

The built-in rule accepts only the supported version, a complete and coherent fact schema, a Choice question, and a CONTINUE baseline. The Goal must remain eligible for continuation, with valid budget and criterion data and no Guard or Policy Override. A valid stale actual CONTINUE evaluation can propose RETRY; other supported continuable snapshots propose CONTINUE. The question must also accept that choice. Proposals carry `rule-goal-v2`. Unknown versions, incomplete facts, invalid evaluations, other scenarios, and unsupported states return BASELINE (`rule-v1`, no confidence).

The rule never proposes COMPLETE or bypasses acceptance, permissions, budgets, leases, or domain scheduling constraints, and it does not schedule durable retries itself. `confidence=1` means a complete deterministic policy match, not a calibrated probability of model correctness. The default remains SHADOW: OFF and SHADOW retain the baseline, while ACTIVE requires explicit configuration and domain commit checks.

## Long-task context and progress recovery

This iteration borrows context ownership, tool-result recovery, and plan preservation ideas from [AgentScope Java harness 2.0.4](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4/agentscope-harness) within the existing runtime. It does not fully integrate HarnessAgent or add an external JEV service. The actual model view remains separate from canonical conversation records; compacting a display does not rewrite durable facts or authorization.

When prefix budgeting is enabled, regular and automatic progress projections retain complete JSON entry boundaries and fit within the smaller of the current ledger allowance and an estimated 2000 tokens. Priority is BLOCKED, IN_PROGRESS, PENDING, then DONE, with status totals and omitted counts. Display keys, labels, and notes may be shortened; canonical entries stay complete. Very small budgets may omit the regular display. Pinned skill constraints remain complete in a separate block and still count toward the actual prompt; optional display limits cannot silently weaken them. Disabled budgeting and the legacy no-argument renderer retain compatibility behavior.

Read-only `get_progress` obtains the current conversation from tool execution context and cannot select another conversation. Without a selector it pages entries (default 10, maximum 20). An exact original `stepKey` or overview `entryIndex` selects complete key, label, note, or the default serialized record text. Field chunks default to 256 and allow at most 512 UTF-16 characters; follow `nextOffset` until null. Chunks preserve surrogate pairs. Each JSON response is limited to 2400 characters, so escaping can reduce the returned page or chunk. Use entryIndex for shortened key previews or ambiguous keys across origins; refresh overview indices after ledger mutations.

Reading progress does not mutate the ledger, create execution receipts, or trigger automatic progress feedback. Model-authored DONE values, labels, and notes are progress claims, not substitutes for actual tool results or Goal acceptance. Mark DONE after receiving a verifiable successful result; independent operations still execute and verify through the existing tool chain.

TokenEstimator accounts for structured tool responses and tool-call arguments. Window control accounts for the actual non-history prefix, active tool schemas, history, and output reserve without double counting. Optional tail-history floors cannot exceed the remaining allowance. Retries should use the current compacted model view, preserve the prefix, original objective, and tool pairs, and obey the finite model-call ceiling. If mandatory content cannot fit, report a clear context error instead of silently slicing skill constraints or repeatedly sending oversized requests. Controlled regression checks establish these boundaries, not real-model accuracy or efficiency improvements.

## Real-model validation boundary for this iteration

Before these changes, clarified multi-stage prompts completed 16 DeepSeek long-task rounds; round 17 stopped with provider HTTP 402 (insufficient balance). Failed artifacts remain available, and two completed rounds required manual semantic review of final-answer consistency. Post-change real-model runs await usable balance or a configured available model. This baseline is not post-change acceptance evidence and does not establish a performance improvement.


Pre-run history compaction and retry compaction share the same model-call guard. Actual pre-run summary calls and provider-reported token/cache usage seed the initial run state; cancellation after a successful summary preserves that usage and stops further execution. Finite call budgets reserve a subsequent reasoning opportunity, and known oversized summary prompts stop before transmission. Legacy entry points without a configured window remain compatible. Output reserves follow actual model options, including OpenAI maxCompletionTokens and Anthropic thinking overhead. Window estimates are not exact billing measurements.
