package com.agentframework.perception;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight perception for tests: drains working memory USER/TOOL entries.
 *
 * <p><b>PE-1 fix:</b> TrustTier is now derived from the entry's taint label
 * rather than unconditionally set to {@link TrustTier#HIGH}.
 * <ul>
 *   <li>{@link TaintLabel#HOSTILE}  → {@link TrustTier#UNTRUSTED}</li>
 *   <li>{@link TaintLabel#EXTERNAL} → {@link TrustTier#LOW}</li>
 *   <li>All others                  → {@link TrustTier#HIGH}</li>
 * </ul>
 * This prevents hostile-tainted working-memory entries from being presented
 * to the reasoning engine with an elevated trust level.
 */
public class SimplePerception implements Perception {
    public Observations perceive(ExecutionContext ctx) {
        List<Observation> obs = new ArrayList<>();
        ctx.workingMemory().getUnprocessed().stream()
           .filter(e -> e.origin() == Origin.USER || e.origin() == Origin.TOOL)
           .forEach(e -> {
               obs.add(new Observation(e.content(), e.origin(),
                   trustTierFor(e.taintLabel()), e.taintLabel(), Instant.now(), "wm"));
               ctx.workingMemory().markProcessed(e.id());
           });
        if (obs.isEmpty()) {
            ctx.goalStack().current().ifPresent(g ->
                obs.add(new Observation(g.description(), Origin.SYSTEM,
                    TrustTier.HIGH, Instant.now(), "goal")));
        }
        return Observations.of(obs);
    }

    /**
     * PE-1 fix: maps a taint label to the appropriate trust tier.
     */
    private static TrustTier trustTierFor(TaintLabel taint) {
        return switch (taint) {
            case HOSTILE  -> TrustTier.UNTRUSTED;
            case EXTERNAL -> TrustTier.LOW;
            default       -> TrustTier.HIGH;
        };
    }
}
