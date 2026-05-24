package com.agentframework.tests;

import com.agentframework.action.*;
import com.agentframework.action.middleware.*;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import com.agentframework.hitl.*;
import com.agentframework.observability.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour-focused tests for the 20 classes that were below the JaCoCo
 * line/branch thresholds (0.80 / 0.75).
 *
 * <p>Target classes:
 * <ol>
 *   <li>CachingMiddleware (+ InProcessCacheStore)</li>
 *   <li>CircuitBreakerMiddleware</li>
 *   <li>RetryMiddleware</li>
 *   <li>RateLimitingMiddleware</li>
 *   <li>HumanApprovalMiddleware</li>
 *   <li>LoggingMiddleware</li>
 *   <li>ToolMiddlewarePipeline (standard + builder)</li>
 *   <li>SafetyActionValidator</li>
 *   <li>SemanticActionValidator</li>
 *   <li>SchemaActionValidator</li>
 *   <li>TaintActionValidator</li>
 *   <li>ToolContract (factory methods + SideEffectClass enum)</li>
 *   <li>ToolFilter</li>
 *   <li>OperationalParams</li>
 *   <li>SimpleToolRegistry (all methods)</li>
 *   <li>ToolException</li>
 *   <li>InMemoryExecutionStore</li>
 *   <li>AutoApprovalService</li>
 *   <li>AutoRejectService</li>
 *   <li>ValidationVerdict</li>
 * </ol>
 */
public class MiddlewareCoverageTest {

    // ─────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────

    private static DefaultExecutionContext ctx() {
        return new DefaultExecutionContext(
            Task.builder().instruction("coverage-test").build(), "tenant1", "user1");
    }

    private static ToolInvocation readOnlyInvocation(String name, Map<String, Object> args) {
        return readOnlyInvocation(name, args, ctx());
    }

    private static ToolInvocation readOnlyInvocation(String name, Map<String, Object> args,
                                                      DefaultExecutionContext ctx) {
        ToolContract contract = ToolContract.readOnly(name, "1.0", "A read-only tool");
        return new ToolInvocation(contract, args, ctx, ValidationVerdict.ok());
    }

    private static ToolInvocation writeInvocation(String name, Map<String, Object> args) {
        ToolContract contract = ToolContract.write(name, "1.0", "A write tool");
        return new ToolInvocation(contract, args, ctx(), ValidationVerdict.ok());
    }

    private static ToolInvocation approvalInvocation(String name, DefaultExecutionContext ctx,
                                                       boolean requiresApproval) {
        ToolContract contract = ToolContract.irreversible(name, "1.0", "Irreversible tool");
        ValidationVerdict verdict = requiresApproval
            ? ValidationVerdict.requireApproval("needs human approval")
            : ValidationVerdict.ok();
        return new ToolInvocation(contract, Map.of(), ctx, verdict);
    }

    // ─────────────────────────────────────────────────────────────────
    // 1 · ValidationVerdict
    // ─────────────────────────────────────────────────────────────────

    @Test
    void validationVerdict_ok_isPassed() {
        ValidationVerdict v = ValidationVerdict.ok();
        assertTrue(v.isPassed());
        assertFalse(v.requiresApproval());
        assertNull(v.reason());
        assertEquals("PASSED", v.toString());
    }

    @Test
    void validationVerdict_failed_isNotPassed() {
        ValidationVerdict v = ValidationVerdict.failed("bad arg");
        assertFalse(v.isPassed());
        assertFalse(v.requiresApproval());
        assertEquals("bad arg", v.reason());
        assertTrue(v.toString().startsWith("FAILED:"));
    }

    @Test
    void validationVerdict_requireApproval_isNotPassedButFlagged() {
        ValidationVerdict v = ValidationVerdict.requireApproval("must approve");
        assertFalse(v.isPassed());
        assertTrue(v.requiresApproval());
        assertEquals("must approve", v.reason());
        assertTrue(v.toString().startsWith("NEEDS_APPROVAL:"));
    }

    @SuppressWarnings("deprecation")
    @Test
    void validationVerdict_passed_delegatesToOk() {
        ValidationVerdict v = ValidationVerdict.passed();
        assertTrue(v.isPassed());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2 · ToolException
    // ─────────────────────────────────────────────────────────────────

    @Test
    void toolException_twoArgConstructor() {
        ToolException e = new ToolException("E001", "something broke");
        assertEquals("E001", e.errorCode());
        assertEquals("something broke", e.getMessage());
    }

    @Test
    void toolException_threeArgConstructorPreservesCause() {
        RuntimeException cause = new RuntimeException("root");
        ToolException e = new ToolException("E002", "wrapped", cause);
        assertEquals("E002", e.errorCode());
        assertSame(cause, e.getCause());
    }

    // ─────────────────────────────────────────────────────────────────
    // 3 · OperationalParams
    // ─────────────────────────────────────────────────────────────────

    @Test
    void operationalParams_recordAccessors() {
        OperationalParams p = new OperationalParams(Duration.ofSeconds(10), 5, true, "reqId");
        assertEquals(Duration.ofSeconds(10), p.timeout());
        assertEquals(5, p.maxRetries());
        assertTrue(p.idempotent());
        assertEquals("reqId", p.idempotencyKeyField());
    }

    // ─────────────────────────────────────────────────────────────────
    // 4 · ToolContract – factory methods and all SideEffectClass values
    // ─────────────────────────────────────────────────────────────────

    @Test
    void toolContract_readOnly_hasCorrectSideEffect() {
        ToolContract c = ToolContract.readOnly("tool", "1.0", "desc");
        assertEquals(ToolContract.SideEffectClass.READ_ONLY, c.sideEffect());
        assertTrue(c.operationalParams().idempotent());
        assertEquals(3, c.operationalParams().maxRetries());
    }

    @Test
    void toolContract_write_hasNonIdempotentSideEffect() {
        ToolContract c = ToolContract.write("tool", "1.0", "desc");
        assertEquals(ToolContract.SideEffectClass.NON_IDEMPOTENT_WRITE, c.sideEffect());
        assertFalse(c.operationalParams().idempotent());
        assertEquals(1, c.operationalParams().maxRetries());
    }

    @Test
    void toolContract_irreversible_hasCorrectSideEffect() {
        ToolContract c = ToolContract.irreversible("tool", "1.0", "desc");
        assertEquals(ToolContract.SideEffectClass.IRREVERSIBLE, c.sideEffect());
        assertEquals(0, c.operationalParams().maxRetries());
    }

    @Test
    void toolContract_highBlastRadius_hasCorrectSideEffect() {
        ToolContract c = ToolContract.highBlastRadius("tool", "1.0", "desc");
        assertEquals(ToolContract.SideEffectClass.HIGH_BLAST_RADIUS, c.sideEffect());
        assertEquals(0, c.operationalParams().maxRetries());
    }

    @Test
    void toolContract_sideEffectEnum_allValues() {
        assertEquals(5, ToolContract.SideEffectClass.values().length);
    }

    // ─────────────────────────────────────────────────────────────────
    // 5 · ToolFilter
    // ─────────────────────────────────────────────────────────────────

    @Test
    void toolFilter_all_matchesAnyContract() {
        ToolContract c = ToolContract.readOnly("any", "1.0", "d");
        assertTrue(ToolFilter.all().matches(c));
    }

    @Test
    void toolFilter_byName_matchesOnlyNamedContract() {
        ToolContract c = ToolContract.readOnly("target", "1.0", "d");
        ToolFilter byName = new ToolFilter(Set.of("target"), null);
        ToolFilter otherName = new ToolFilter(Set.of("other"), null);
        assertTrue(byName.matches(c));
        assertFalse(otherName.matches(c));
    }

    @Test
    void toolFilter_bySideEffect_matchesOnlyMatchingSideEffect() {
        ToolContract ro = ToolContract.readOnly("t", "1.0", "d");
        ToolContract irr = ToolContract.irreversible("t2", "1.0", "d");
        ToolFilter f = new ToolFilter(null, Set.of(ToolContract.SideEffectClass.READ_ONLY));
        assertTrue(f.matches(ro));
        assertFalse(f.matches(irr));
    }

    @Test
    void toolFilter_combined_requiresBothConditions() {
        ToolContract c = ToolContract.readOnly("target", "1.0", "d");
        ToolFilter match = new ToolFilter(Set.of("target"),
            Set.of(ToolContract.SideEffectClass.READ_ONLY));
        ToolFilter wrongName = new ToolFilter(Set.of("other"),
            Set.of(ToolContract.SideEffectClass.READ_ONLY));
        assertTrue(match.matches(c));
        assertFalse(wrongName.matches(c));
    }

    // ─────────────────────────────────────────────────────────────────
    // 6 · SimpleToolRegistry
    // ─────────────────────────────────────────────────────────────────

    @Test
    void simpleToolRegistry_registerAndLookup() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        ToolContract c = ToolContract.readOnly("ping", "1.0", "ping tool");
        reg.register(c, (args, ctx) -> ToolResult.ok("pong"));
        assertNotNull(reg.lookup("ping"));
        assertEquals("ping", reg.lookup("ping").name());
    }

    @Test
    void simpleToolRegistry_lookupUnknownReturnsNull() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        assertNull(reg.lookup("no-such-tool"));
    }

    @Test
    void simpleToolRegistry_deregisterRemovesTool() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("t", "1.0", "d"), (a, c) -> ToolResult.ok("x"));
        reg.deregister("t");
        assertNull(reg.lookup("t"));
    }

    @Test
    void simpleToolRegistry_list_filtersCorrectly() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("r1", "1.0", "read"), (a, c) -> ToolResult.ok("x"));
        reg.register(ToolContract.irreversible("i1", "1.0", "irrev"), (a, c) -> ToolResult.ok("y"));
        List<ToolContract> reads = reg.list(
            new ToolFilter(null, Set.of(ToolContract.SideEffectClass.READ_ONLY)));
        assertTrue(reads.stream().allMatch(c -> c.sideEffect() == ToolContract.SideEffectClass.READ_ONLY));
        assertFalse(reads.stream().anyMatch(c -> c.name().equals("i1")));
    }

    @Test
    void simpleToolRegistry_schemaFor_presentAndAbsent() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("t", "1.0", "d"), (a, c) -> ToolResult.ok("x"));
        assertTrue(reg.schemaFor("t").isPresent());
        assertFalse(reg.schemaFor("missing").isPresent());
    }

    @Test
    void simpleToolRegistry_topK_ranksByDescriptionKeywords() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("web-search", "1.0", "search the web for information"),
            (a, c) -> null);
        reg.register(ToolContract.readOnly("calculator", "1.0", "calculate arithmetic expressions"),
            (a, c) -> null);
        List<ToolContract> top = reg.topK("search web", 1);
        assertEquals(1, top.size());
        assertEquals("web-search", top.get(0).name());
    }

    @Test
    void simpleToolRegistry_registerWithAlias() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("canonical", "1.0", "the real tool"), (a, c) -> ToolResult.ok("x"));
        reg.registerWithAlias("alias", "canonical", "1.0");
        assertNotNull(reg.lookup("alias"));
        assertEquals("canonical", reg.lookup("alias").name());
    }

    @Test
    void simpleToolRegistry_listVersions() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("tool", "1.0", "v1"), (a, c) -> ToolResult.ok("a"));
        reg.register(ToolContract.readOnly("tool", "2.0", "v2"), (a, c) -> ToolResult.ok("b"));
        List<ToolContract> versions = reg.listVersions("tool");
        assertEquals(2, versions.size());
    }

    @Test
    void simpleToolRegistry_remove_byVersion() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("tool", "1.0", "v1"), (a, c) -> ToolResult.ok("a"));
        reg.remove("tool", "1.0");
        assertTrue(reg.listVersions("tool").isEmpty());
    }

    @Test
    void simpleToolRegistry_findHandler() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        ToolHandler h = (a, c) -> ToolResult.ok("handled");
        reg.register(ToolContract.readOnly("tool", "1.0", "d"), h);
        assertNotNull(reg.findHandler("tool"));
        assertNull(reg.findHandler("ghost"));
    }

    @Test
    void simpleToolRegistry_deprecate_doesNotThrow() {
        SimpleToolRegistry reg = new SimpleToolRegistry();
        reg.register(ToolContract.readOnly("old", "1.0", "d"), (a, c) -> ToolResult.ok("x"));
        assertDoesNotThrow(() -> reg.deprecate("old", "1.0", Instant.now().plusSeconds(3600)));
    }

    // ─────────────────────────────────────────────────────────────────
    // 7 · LoggingMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void loggingMiddleware_passesResultThrough() {
        LoggingMiddleware log = new LoggingMiddleware();
        ToolInvocation inv = readOnlyInvocation("ping", Map.of());
        ToolResult result = log.apply(inv, i -> ToolResult.ok("pong"));
        assertEquals("pong", result.data());
    }

    // ─────────────────────────────────────────────────────────────────
    // 8 · CachingMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void cachingMiddleware_cachesMissOnFirstCallAndHitsOnSecond() {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation inv = readOnlyInvocation("lookup", Map.of("key", "v1"));

        ToolResult r1 = cache.apply(inv, i -> { count[0]++; return ToolResult.ok("data"); });
        ToolResult r2 = cache.apply(inv, i -> { count[0]++; return ToolResult.ok("data"); });

        assertEquals(1, count[0], "handler called only once");
        assertEquals(r1.data(), r2.data(), "same cached data returned");
    }

    @Test
    void cachingMiddleware_differentArgsProduceDifferentCacheEntries() {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation inv1 = readOnlyInvocation("lookup", Map.of("key", "a"));
        ToolInvocation inv2 = readOnlyInvocation("lookup", Map.of("key", "b"));

        cache.apply(inv1, i -> { count[0]++; return ToolResult.ok("A"); });
        cache.apply(inv2, i -> { count[0]++; return ToolResult.ok("B"); });

        assertEquals(2, count[0], "different args → two cache entries");
    }

    @Test
    void cachingMiddleware_writeTool_bypassesCache() {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation inv = writeInvocation("write-op", Map.of("data", "x"));

        cache.apply(inv, i -> { count[0]++; return ToolResult.write("written"); });
        cache.apply(inv, i -> { count[0]++; return ToolResult.write("written"); });

        assertEquals(2, count[0], "non-read-only calls are never cached");
    }

    @Test
    void cachingMiddleware_nullContract_bypassesCache() {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation inv = new ToolInvocation(null, Map.of(), ctx(), ValidationVerdict.ok());

        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("x"); });
        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("x"); });

        assertEquals(2, count[0], "null contract → always forward");
    }

    @Test
    void cachingMiddleware_expiredEntryRefetches() throws InterruptedException {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMillis(50));
        ToolInvocation inv = readOnlyInvocation("t", Map.of("k", "v"));

        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("old"); });
        Thread.sleep(100); // let TTL expire
        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("fresh"); });

        assertEquals(2, count[0], "expired cache entry triggers a new fetch");
    }

    @Test
    void cachingMiddleware_invalidateForTenant_removesOnlyTenantEntries() {
        int[] count = {0};
        DefaultExecutionContext ctxA = new DefaultExecutionContext(
            Task.builder().instruction("t").build(), "tenantA", "u");
        DefaultExecutionContext ctxB = new DefaultExecutionContext(
            Task.builder().instruction("t").build(), "tenantB", "u");

        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation invA = readOnlyInvocation("t", Map.of("k", "1"), ctxA);
        ToolInvocation invB = readOnlyInvocation("t", Map.of("k", "1"), ctxB);

        cache.apply(invA, i -> { count[0]++; return ToolResult.ok("a"); });
        cache.apply(invB, i -> { count[0]++; return ToolResult.ok("b"); });

        cache.invalidateForTenant("tenantA");

        cache.apply(invA, i -> { count[0]++; return ToolResult.ok("a2"); }); // miss → refetch
        cache.apply(invB, i -> { count[0]++; return ToolResult.ok("b"); });  // still cached

        assertEquals(3, count[0], "only tenantA cache was invalidated");
    }

    @Test
    void cachingMiddleware_invalidateAll_clearsEverything() {
        int[] count = {0};
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(5));
        ToolInvocation inv = readOnlyInvocation("t", Map.of("k", "1"));

        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("x"); });
        cache.invalidateAll();
        cache.apply(inv, i -> { count[0]++; return ToolResult.ok("x"); });

        assertEquals(2, count[0], "invalidateAll forces a new fetch");
    }

    // ─────────────────────────────────────────────────────────────────
    // 9 · CircuitBreakerMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void circuitBreaker_closedOnSuccess() {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(3, 60_000);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());
        ToolResult r = cb.apply(inv, i -> ToolResult.ok("ok"));
        assertFalse(cb.isOpen(), "circuit stays closed after success");
        assertEquals("ok", r.data());
    }

    @Test
    void circuitBreaker_opensAfterThreshold() {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(2, 60_000);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class,
                () -> cb.apply(inv, x -> { throw new RuntimeException("fail"); }));
        }
        assertTrue(cb.isOpen(), "circuit opens after 2 failures");
    }

    @Test
    void circuitBreaker_openStateThrowsImmediately() {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(1, 60_000);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        assertThrows(RuntimeException.class,
            () -> cb.apply(inv, x -> { throw new RuntimeException("fail"); }));
        assertTrue(cb.isOpen());

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> cb.apply(inv, x -> ToolResult.ok("should not reach")));
        assertTrue(ex.getMessage().contains("Circuit open"), "message mentions circuit open");
    }

    @Test
    void circuitBreaker_halfOpenAfterTimeout() throws InterruptedException {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(1, 50);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        assertThrows(RuntimeException.class,
            () -> cb.apply(inv, x -> { throw new RuntimeException("fail"); }));

        Thread.sleep(100); // let timeout pass

        ToolResult r = cb.apply(inv, i -> ToolResult.ok("recovered"));
        assertEquals("recovered", r.data(), "circuit auto-resets after timeout");
        assertFalse(cb.isOpen(), "circuit closed after successful half-open probe");
    }

    @Test
    void circuitBreaker_reset_closesCircuit() {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(1, 60_000);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        assertThrows(RuntimeException.class,
            () -> cb.apply(inv, x -> { throw new RuntimeException("fail"); }));
        cb.reset();
        assertFalse(cb.isOpen(), "reset() closes circuit");
    }

    @Test
    void circuitBreaker_successAfterPartialFailuresResetsCounter() {
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(3, 60_000);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        assertThrows(RuntimeException.class,
            () -> cb.apply(inv, x -> { throw new RuntimeException("fail"); }));
        cb.apply(inv, i -> ToolResult.ok("ok")); // success resets failure count
        assertFalse(cb.isOpen(), "success resets the failure counter");
    }

    // ─────────────────────────────────────────────────────────────────
    // 10 · RetryMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void retryMiddleware_succeedsFirstAttempt_noRetryCountAttached() {
        RetryMiddleware retry = new RetryMiddleware(3, 1);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());
        ToolResult r = retry.apply(inv, i -> ToolResult.ok("done"));
        assertEquals(0, r.retryCount(), "first-attempt success → retryCount stays 0");
    }

    @Test
    void retryMiddleware_retriesAndSucceedsOnThirdAttempt() {
        int[] calls = {0};
        RetryMiddleware retry = new RetryMiddleware(3, 1, 1, new Random(0));
        ToolInvocation inv = readOnlyInvocation("t", Map.of());
        ToolResult r = retry.apply(inv, i -> {
            if (++calls[0] < 3) throw new RuntimeException("temporary");
            return ToolResult.ok("success");
        });
        assertEquals("success", r.data());
        assertEquals(2, r.retryCount(), "retryCount reflects number of retries");
        assertEquals(3, calls[0]);
    }

    @Test
    void retryMiddleware_exhaustsRetriesAndRethrows() {
        int[] calls = {0};
        RetryMiddleware retry = new RetryMiddleware(2, 1, 1, new Random(0));
        ToolInvocation inv = readOnlyInvocation("t", Map.of());
        assertThrows(RuntimeException.class, () ->
            retry.apply(inv, i -> { calls[0]++; throw new RuntimeException("always fails"); }));
        assertEquals(3, calls[0], "1 initial + 2 retries = 3 total calls");
    }

    @Test
    void retryMiddleware_nonIdempotentTool_zeroRetries() {
        int[] calls = {0};
        RetryMiddleware retry = new RetryMiddleware(3, 1);
        ToolInvocation inv = writeInvocation("write", Map.of());
        assertThrows(RuntimeException.class, () ->
            retry.apply(inv, i -> { calls[0]++; throw new RuntimeException("fail"); }));
        assertEquals(1, calls[0], "non-idempotent tool capped at 0 retries = 1 attempt only");
    }

    @Test
    void retryMiddleware_nullContract_usesDefaultRetries() {
        int[] calls = {0};
        RetryMiddleware retry = new RetryMiddleware(2, 1, 1, new Random(0));
        ToolInvocation inv = new ToolInvocation(null, Map.of(), ctx(), ValidationVerdict.ok());
        assertThrows(RuntimeException.class, () ->
            retry.apply(inv, i -> { calls[0]++; throw new RuntimeException("fail"); }));
        assertEquals(3, calls[0], "null contract → uses defaultMaxRetries=2 → 3 total calls");
    }

    @Test
    void retryMiddleware_negativeDefaultRetries_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> new RetryMiddleware(-1, 10));
    }

    @Test
    void retryMiddleware_zeroBaseBackoff_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> new RetryMiddleware(1, 0));
    }

    @Test
    void retryMiddleware_maxBackoffLessThanBase_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> new RetryMiddleware(1, 100, 50, new Random()));
    }

    // ─────────────────────────────────────────────────────────────────
    // 11 · RateLimitingMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void rateLimiting_permitsUpToMaxConcurrent() {
        RateLimitingMiddleware rl = new RateLimitingMiddleware(1);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());
        ToolResult r = rl.apply(inv, i -> ToolResult.ok("ok"));
        assertEquals("ok", r.data());
    }

    @Test
    void rateLimiting_exceedingLimitThrows() throws InterruptedException {
        RateLimitingMiddleware rl = new RateLimitingMiddleware(1);
        ToolInvocation inv = readOnlyInvocation("t", Map.of());

        // Hold the semaphore from a background thread
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> rl.apply(inv, i -> {
            holding.countDown();
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            return ToolResult.ok("held");
        }));
        holder.start();
        holding.await();

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> rl.apply(inv, i -> ToolResult.ok("should not run")));
        assertTrue(ex.getMessage().contains("Rate limit exceeded"));

        release.countDown();
        holder.join(1000);
    }

    @Test
    void rateLimiting_differentToolsHaveSeparateSemaphores() {
        RateLimitingMiddleware rl = new RateLimitingMiddleware(1);
        ToolInvocation inv1 = readOnlyInvocation("toolA", Map.of());
        ToolInvocation inv2 = readOnlyInvocation("toolB", Map.of());
        // Both should succeed when called sequentially (different semaphores)
        assertDoesNotThrow(() -> rl.apply(inv1, i -> ToolResult.ok("a")));
        assertDoesNotThrow(() -> rl.apply(inv2, i -> ToolResult.ok("b")));
    }

    // ─────────────────────────────────────────────────────────────────
    // 12 · HumanApprovalMiddleware
    // ─────────────────────────────────────────────────────────────────

    @Test
    void humanApproval_noApprovalRequired_forwardsDirectly() {
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            new AutoApprovalService(), new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("t", ctx, false);
        ToolResult r = h.apply(inv, i -> ToolResult.ok("done"));
        assertEquals("done", r.data());
    }

    @Test
    void humanApproval_approved_executesTool() {
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            new AutoApprovalService(), new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("irrev", ctx, true);
        ToolResult r = h.apply(inv, i -> ToolResult.ok("executed"));
        assertEquals("executed", r.data());
    }

    @Test
    void humanApproval_rejected_returnsRejectedResult() {
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            new AutoRejectService(), new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("irrev", ctx, true);
        ToolResult r = h.apply(inv, i -> ToolResult.ok("should not run"));
        assertTrue(r.data().toString().startsWith("REJECTED:"), "rejected result");
    }

    @Test
    void humanApproval_timeout_abortsContext() {
        ApprovalService timeoutService = p -> {
            throw new ApprovalTimeoutException("timed out");
        };
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            timeoutService, new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("irrev", ctx, true);
        ToolResult r = h.apply(inv, i -> ToolResult.ok("should not run"));
        assertTrue(r.data().toString().contains("approval_timeout"), "timeout result");
    }

    @Test
    void humanApproval_modified_executesWithUpdatedArguments() {
        ToolCall updatedCall = new ToolCall("irrev", Map.of("safe", "true"), "modified");
        ApprovalService modifyService = p -> new ApprovalDecision.Modified(updatedCall);
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            modifyService, new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("irrev", ctx, true);

        ToolResult r = h.apply(inv, i -> ToolResult.ok(i.arguments().get("safe")));
        assertEquals("true", r.data(), "modified arguments forwarded to handler");
    }

    @Test
    void humanApproval_escalated_returnsRejectedResult() {
        ApprovalService escalateService = p -> new ApprovalDecision.Escalated("needs manager");
        HumanApprovalMiddleware h = new HumanApprovalMiddleware(
            escalateService, new InMemoryExecutionStore());
        DefaultExecutionContext ctx = ctx();
        ToolInvocation inv = approvalInvocation("irrev", ctx, true);
        ToolResult r = h.apply(inv, i -> ToolResult.ok("should not run"));
        assertTrue(r.data().toString().startsWith("REJECTED:"), "escalated falls through to default reject");
    }

    // ─────────────────────────────────────────────────────────────────
    // 13 · ToolMiddlewarePipeline
    // ─────────────────────────────────────────────────────────────────

    @Test
    void pipeline_standard_executesHandlerThroughAllStages() {
        LoggingMiddleware log = new LoggingMiddleware();
        CircuitBreakerMiddleware cb = new CircuitBreakerMiddleware(3, 60_000);
        RateLimitingMiddleware rl = new RateLimitingMiddleware(10);
        CachingMiddleware cache = new CachingMiddleware(Duration.ofMinutes(1));
        RetryMiddleware retry = new RetryMiddleware(0, 1);

        ToolMiddleware pipeline = ToolMiddlewarePipeline.standard(
            log, cb, rl, cache, retry, inv -> ToolResult.ok("pipeline-result"));

        ToolInvocation inv = readOnlyInvocation("t", Map.of("x", "1"));
        ToolResult r = pipeline.apply(inv, null);
        assertEquals("pipeline-result", r.data());
    }

    @Test
    void pipeline_standard_nullArgThrows() {
        assertThrows(NullPointerException.class, () ->
            ToolMiddlewarePipeline.standard(null, null, null, null, null, null));
    }

    @Test
    void pipeline_builder_singleStage_passesThrough() {
        ToolMiddleware pipeline = ToolMiddlewarePipeline.builder(
            inv -> ToolResult.ok("built"))
            .addStage(new LoggingMiddleware())
            .build();
        ToolResult r = pipeline.apply(readOnlyInvocation("t", Map.of()), null);
        assertEquals("built", r.data());
    }

    @Test
    void pipeline_builder_noStages_executesHandlerDirectly() {
        ToolMiddleware pipeline = ToolMiddlewarePipeline.builder(
            inv -> ToolResult.ok("direct"))
            .build();
        ToolResult r = pipeline.apply(readOnlyInvocation("t", Map.of()), null);
        assertEquals("direct", r.data());
    }

    @Test
    void pipeline_builder_nullStageThrows() {
        assertThrows(NullPointerException.class, () ->
            ToolMiddlewarePipeline.builder(inv -> ToolResult.ok("x"))
                .addStage(null));
    }

    @Test
    void pipeline_builder_nullHandlerThrows() {
        assertThrows(NullPointerException.class, () ->
            ToolMiddlewarePipeline.builder(null));
    }

    @Test
    void pipeline_notInstantiable() {
        assertThrows(Exception.class, () -> {
            var ctor = ToolMiddlewarePipeline.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    @Test
    void pipeline_builder_multipleStagesAppliedOutermostFirst() {
        List<String> order = new ArrayList<>();
        ToolMiddleware stageA = (inv, next) -> {
            order.add("A-before");
            ToolResult r = next.apply(inv);
            order.add("A-after");
            return r;
        };
        ToolMiddleware stageB = (inv, next) -> {
            order.add("B-before");
            ToolResult r = next.apply(inv);
            order.add("B-after");
            return r;
        };
        ToolMiddleware pipeline = ToolMiddlewarePipeline.builder(inv -> ToolResult.ok("done"))
            .addStage(stageA)
            .addStage(stageB)
            .build();
        pipeline.apply(readOnlyInvocation("t", Map.of()), null);
        assertEquals(List.of("A-before", "B-before", "B-after", "A-after"), order,
            "outermost stage (A) wraps innermost stage (B)");
    }

    // ─────────────────────────────────────────────────────────────────
    // 14 · SafetyActionValidator
    // ─────────────────────────────────────────────────────────────────

    @Test
    void safetyValidator_readOnly_passesWithOk() {
        SafetyActionValidator v = new SafetyActionValidator();
        ToolCall call = new ToolCall("t", Map.of(), "");
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d");
        ValidationVerdict verdict = v.validate(call, contract, ctx());
        assertTrue(verdict.isPassed());
    }

    @Test
    void safetyValidator_idempotentWrite_passesWithOk() {
        SafetyActionValidator v = new SafetyActionValidator();
        ToolContract contract = new ToolContract("t", "1.0", "d",
            JsonSchema.empty(), JsonSchema.empty(),
            ToolContract.SideEffectClass.IDEMPOTENT_WRITE,
            new OperationalParams(Duration.ofSeconds(10), 1, true, null),
            false, null, Set.of());
        assertTrue(v.validate(new ToolCall("t", Map.of(), ""), contract, ctx()).isPassed());
    }

    @Test
    void safetyValidator_nonIdempotentWrite_passesWithOk() {
        SafetyActionValidator v = new SafetyActionValidator();
        ToolContract contract = ToolContract.write("t", "1.0", "d");
        assertTrue(v.validate(new ToolCall("t", Map.of(), ""), contract, ctx()).isPassed());
    }

    @Test
    void safetyValidator_irreversible_requiresApproval() {
        SafetyActionValidator v = new SafetyActionValidator();
        ToolCall call = new ToolCall("nuke", Map.of(), "");
        ToolContract contract = ToolContract.irreversible("nuke", "1.0", "destroy");
        ValidationVerdict verdict = v.validate(call, contract, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.requiresApproval());
        assertTrue(verdict.reason().contains("nuke"));
    }

    @Test
    void safetyValidator_highBlastRadius_requiresApproval() {
        SafetyActionValidator v = new SafetyActionValidator();
        ToolCall call = new ToolCall("deploy-all", Map.of(), "");
        ToolContract contract = ToolContract.highBlastRadius("deploy-all", "1.0", "deploy");
        ValidationVerdict verdict = v.validate(call, contract, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.requiresApproval());
    }

    @Test
    void safetyValidator_nullContract_returnsOk() {
        SafetyActionValidator v = new SafetyActionValidator();
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), null, ctx());
        assertTrue(verdict.isPassed(), "null contract is handled gracefully");
    }

    // ─────────────────────────────────────────────────────────────────
    // 15 · SemanticActionValidator
    // ─────────────────────────────────────────────────────────────────

    @Test
    void semanticValidator_noActiveGoal_returnsOk() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx(); // goal stack empty
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d");
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), contract, ctx);
        assertTrue(verdict.isPassed(), "no active goal → pass through");
    }

    @Test
    void semanticValidator_completedGoal_blocksToolCall() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx();
        ctx.goalStack().push(new Goal("g1", null, GoalStatus.COMPLETED, "finish task", List.of(), null));
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d");
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), contract, ctx);
        assertFalse(verdict.isPassed(), "completed goal blocks new tool calls");
        assertTrue(verdict.reason().contains("COMPLETED"));
    }

    @Test
    void semanticValidator_failedGoal_blocksToolCall() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx();
        ctx.goalStack().push(new Goal("g1", null, GoalStatus.FAILED, "do work", List.of(), null));
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d");
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), contract, ctx);
        assertFalse(verdict.isPassed(), "failed goal blocks new tool calls");
        assertTrue(verdict.reason().contains("FAILED"));
    }

    @Test
    void semanticValidator_irreversibleOnPendingAtCycle0_blocked() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx(); // cycleCount == 0
        ctx.goalStack().push(new Goal("g1", null, GoalStatus.PENDING, "do work", List.of(), null));
        ToolContract contract = ToolContract.irreversible("destroy", "1.0", "irreversible");
        ValidationVerdict verdict = v.validate(
            new ToolCall("destroy", Map.of(), ""), contract, ctx);
        assertFalse(verdict.isPassed(), "irreversible on PENDING at cycle 0 is blocked");
        assertTrue(verdict.reason().contains("Irreversible"));
    }

    @Test
    void semanticValidator_irreversibleOnPendingAfterCycle0_allowed() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx();
        ctx.goalStack().push(new Goal("g1", null, GoalStatus.PENDING, "do work", List.of(), null));
        ctx.incrementCycle(); // cycleCount == 1
        ToolContract contract = ToolContract.irreversible("destroy", "1.0", "irreversible");
        ValidationVerdict verdict = v.validate(
            new ToolCall("destroy", Map.of(), ""), contract, ctx);
        assertTrue(verdict.isPassed(), "irreversible on PENDING after cycle 0 passes");
    }

    @Test
    void semanticValidator_activeGoal_readOnly_passesThrough() {
        SemanticActionValidator v = new SemanticActionValidator();
        DefaultExecutionContext ctx = ctx();
        ctx.goalStack().push(new Goal("g1", null, GoalStatus.ACTIVE, "in progress", List.of(), null));
        ToolContract contract = ToolContract.readOnly("search", "1.0", "search");
        ValidationVerdict verdict = v.validate(
            new ToolCall("search", Map.of(), ""), contract, ctx);
        assertTrue(verdict.isPassed(), "read-only on ACTIVE goal passes");
    }

    // ─────────────────────────────────────────────────────────────────
    // 16 · SchemaActionValidator
    // ─────────────────────────────────────────────────────────────────

    @Test
    void schemaValidator_nullContract_failsWithMessage() {
        SchemaActionValidator v = new SchemaActionValidator();
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), null, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.reason().contains("No contract"));
    }

    @Test
    void schemaValidator_nullArguments_failsWithMessage() {
        SchemaActionValidator v = new SchemaActionValidator();
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d");
        ValidationVerdict verdict = v.validate(new ToolCall("t", null, ""), contract, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.reason().contains("Null arguments"));
    }

    @Test
    void schemaValidator_emptySchema_passesAlways() {
        SchemaActionValidator v = new SchemaActionValidator();
        ToolContract contract = ToolContract.readOnly("t", "1.0", "d"); // schema is {}
        ValidationVerdict verdict = v.validate(new ToolCall("t", Map.of(), ""), contract, ctx());
        assertTrue(verdict.isPassed());
    }

    @Test
    void schemaValidator_missingRequiredField_fails() {
        SchemaActionValidator v = new SchemaActionValidator();
        String schema = "{\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}";
        ToolContract contract = new ToolContract("weather", "1.0", "weather tool",
            JsonSchema.of(schema), JsonSchema.empty(),
            ToolContract.SideEffectClass.READ_ONLY,
            new OperationalParams(Duration.ofSeconds(10), 1, true, null),
            false, null, Set.of());
        ValidationVerdict verdict = v.validate(
            new ToolCall("weather", Map.of(), ""), contract, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.reason().contains("city"));
    }

    @Test
    void schemaValidator_wrongType_fails() {
        SchemaActionValidator v = new SchemaActionValidator();
        String schema = "{\"properties\":{\"count\":{\"type\":\"number\"}}}";
        ToolContract contract = new ToolContract("t", "1.0", "d",
            JsonSchema.of(schema), JsonSchema.empty(),
            ToolContract.SideEffectClass.READ_ONLY,
            new OperationalParams(Duration.ofSeconds(10), 1, true, null),
            false, null, Set.of());
        ValidationVerdict verdict = v.validate(
            new ToolCall("t", Map.of("count", "not-a-number"), ""), contract, ctx());
        assertFalse(verdict.isPassed());
        assertTrue(verdict.reason().contains("count"));
    }

    @Test
    void schemaValidator_allFieldsPresentAndCorrectType_passes() {
        SchemaActionValidator v = new SchemaActionValidator();
        String schema = "{\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}";
        ToolContract contract = new ToolContract("weather", "1.0", "weather",
            JsonSchema.of(schema), JsonSchema.empty(),
            ToolContract.SideEffectClass.READ_ONLY,
            new OperationalParams(Duration.ofSeconds(10), 1, true, null),
            false, null, Set.of());
        ValidationVerdict verdict = v.validate(
            new ToolCall("weather", Map.of("city", "Paris"), ""), contract, ctx());
        assertTrue(verdict.isPassed());
    }

    // ─────────────────────────────────────────────────────────────────
    // 17 · TaintActionValidator
    // ─────────────────────────────────────────────────────────────────

    @Test
    void taintValidator_noHostileEntries_passesWithoutEvent() {
        List<AgentEvent> events = new ArrayList<>();
        TaintActionValidator v = new TaintActionValidator(events::add);
        DefaultExecutionContext ctx = ctx();
        ValidationVerdict verdict = v.validate(
            new ToolCall("t", Map.of("q", "safe"), ""), null, ctx);
        assertTrue(verdict.isPassed());
        assertTrue(events.isEmpty(), "no event emitted when no hostile entries exist");
    }

    @Test
    void taintValidator_hostileEntryInArgument_blocksCall() {
        List<AgentEvent> events = new ArrayList<>();
        TaintActionValidator v = new TaintActionValidator(events::add);
        DefaultExecutionContext ctx = ctx();
        ctx.workingMemory().add(new WorkingMemoryEntry(
            "hostile-id-1", "injected payload",
            WorkingMemoryTier.ACTIVE, Origin.RETRIEVAL, 0.9,
            Instant.now(), TaintLabel.HOSTILE));

        ValidationVerdict verdict = v.validate(
            new ToolCall("search", Map.of("q", "hostile-id-1"), ""), null, ctx);

        assertFalse(verdict.isPassed(), "call blocked when argument references hostile id");
        assertEquals(1, events.size(), "BLOCK event emitted");
        assertEquals("BLOCK", events.get(0).attributes().get("severity"));
    }

    @Test
    void taintValidator_hostileEntryNotReferencedInArgs_auditsButPasses() {
        List<AgentEvent> events = new ArrayList<>();
        TaintActionValidator v = new TaintActionValidator(events::add);
        DefaultExecutionContext ctx = ctx();
        ctx.workingMemory().add(new WorkingMemoryEntry(
            "hostile-id-2", "injected payload",
            WorkingMemoryTier.ACTIVE, Origin.RETRIEVAL, 0.9,
            Instant.now(), TaintLabel.HOSTILE));

        ValidationVerdict verdict = v.validate(
            new ToolCall("search", Map.of("q", "unrelated-query"), ""), null, ctx);

        assertTrue(verdict.isPassed(), "call passes when argument does not reference hostile id");
        assertEquals(1, events.size(), "WARN audit event emitted");
        assertEquals("WARN", events.get(0).attributes().get("severity"));
    }

    @Test
    void taintValidator_nullArgumentValues_skippedSafely() {
        List<AgentEvent> events = new ArrayList<>();
        TaintActionValidator v = new TaintActionValidator(events::add);
        DefaultExecutionContext ctx = ctx();
        ctx.workingMemory().add(new WorkingMemoryEntry(
            "hostile-id-3", "payload",
            WorkingMemoryTier.ACTIVE, Origin.RETRIEVAL, 0.9,
            Instant.now(), TaintLabel.HOSTILE));

        Map<String, Object> args = new HashMap<>();
        args.put("q", null); // null value must not cause NPE

        assertDoesNotThrow(() -> v.validate(new ToolCall("t", args, ""), null, ctx));
    }

    // ─────────────────────────────────────────────────────────────────
    // 18 · InMemoryExecutionStore
    // ─────────────────────────────────────────────────────────────────

    @Test
    void inMemoryExecutionStore_saveAndLoad() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx();
        ExecutionContext.Snapshot snap = ctx.checkpoint();
        store.save(snap);
        ExecutionContext.Snapshot loaded = store.load(snap.runId());
        assertEquals(snap.runId(), loaded.runId());
    }

    @Test
    void inMemoryExecutionStore_loadUnknown_throws() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertThrows(IllegalArgumentException.class,
            () -> store.load("no-such-run-id"));
    }

    @Test
    void inMemoryExecutionStore_delete_removesSnapshot() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext ctx = ctx();
        ExecutionContext.Snapshot snap = ctx.checkpoint();
        store.save(snap);
        store.delete(snap.runId());
        assertEquals(0, store.size());
        assertThrows(IllegalArgumentException.class, () -> store.load(snap.runId()));
    }

    @Test
    void inMemoryExecutionStore_sizeReflectsStoreCount() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        DefaultExecutionContext c1 = ctx();
        DefaultExecutionContext c2 = ctx();
        store.save(c1.checkpoint());
        store.save(c2.checkpoint());
        assertEquals(2, store.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // 19 · AutoApprovalService
    // ─────────────────────────────────────────────────────────────────

    @Test
    void autoApprovalService_alwaysReturnsApproved() {
        AutoApprovalService svc = new AutoApprovalService();
        ApprovalDecision d = svc.awaitDecision(null);
        assertInstanceOf(ApprovalDecision.Approved.class, d);
    }

    // ─────────────────────────────────────────────────────────────────
    // 20 · AutoRejectService
    // ─────────────────────────────────────────────────────────────────

    @Test
    void autoRejectService_alwaysReturnsRejected() {
        AutoRejectService svc = new AutoRejectService();
        ApprovalDecision d = svc.awaitDecision(null);
        assertInstanceOf(ApprovalDecision.Rejected.class, d);
        assertEquals("auto-rejected in test", ((ApprovalDecision.Rejected) d).reason());
    }
}
