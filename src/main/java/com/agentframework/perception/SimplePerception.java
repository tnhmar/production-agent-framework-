package com.agentframework.perception;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
/** Lightweight perception for tests: just drains working memory USER/TOOL entries. */
public class SimplePerception implements Perception {
    public Observations perceive(ExecutionContext ctx) {
        List<Observation> obs = new ArrayList<>();
        ctx.workingMemory().getUnprocessed().stream()
           .filter(e -> e.origin()==Origin.USER || e.origin()==Origin.TOOL)
           .forEach(e -> {
               obs.add(new Observation(e.content(), e.origin(),
                   TrustTier.HIGH, e.taintLabel(), Instant.now(), "wm"));
               ctx.workingMemory().markProcessed(e.id());
           });
        // if empty add current goal as seed observation
        if (obs.isEmpty()) {
            ctx.goalStack().current().ifPresent(g ->
                obs.add(new Observation(g.description(), Origin.SYSTEM,
                    TrustTier.HIGH, Instant.now(), "goal")));
        }
        return Observations.of(obs);
    }
}
