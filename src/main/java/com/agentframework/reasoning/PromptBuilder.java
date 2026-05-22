package com.agentframework.reasoning;
import com.agentframework.action.ToolRegistry;
import com.agentframework.core.*;
import com.agentframework.foundation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
public class PromptBuilder {
    private final String systemPrompt;
    private final ToolRegistry registry;
    private final int maxContextTokens;

    public PromptBuilder(String systemPrompt, ToolRegistry registry, int maxContextTokens) {
        this.systemPrompt=systemPrompt; this.registry=registry; this.maxContextTokens=maxContextTokens;
    }

    public Prompt build(ExecutionContext ctx, Observations obs, ReasoningStrategy strategy) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new Message(Message.Role.SYSTEM, buildSystem(ctx, strategy)));
        msgs.add(new Message(Message.Role.USER,   buildUser(ctx, obs)));
        ctx.workingMemory().getByOrigin(Origin.TOOL).stream().reduce((a,b)->b)
           .ifPresent(last -> msgs.add(new Message(Message.Role.TOOL_RESULT, last.content())));
        return new Prompt(msgs, InferenceParameters.defaults().withMaxTokens(maxContextTokens));
    }

    private String buildSystem(ExecutionContext ctx, ReasoningStrategy strategy) {
        StringBuilder sb = new StringBuilder(systemPrompt).append("\n\n");
        ctx.goalStack().current().ifPresent(g ->
            sb.append("Goal: ").append(g.description()).append("\n"));
        sb.append("Cycle: ").append(ctx.cycleCount())
          .append(" | Tokens: ").append(ctx.totalTokensUsed())
          .append(" / ").append(ctx.task().maxTokens()).append("\n\n");
        var tools = registry.topK("", 10);
        if (!tools.isEmpty()) {
            sb.append("Available tools:\n");
            tools.forEach(t -> sb.append("  - ").append(t.name()).append(": ")
                .append(t.description()).append("\n"));
        }
        sb.append("\n").append(strategy.outputSchemaDescription());
        if (ctx.isPlanStale())
            sb.append("\n[WARNING] Previous plan invalidated: ").append(ctx.stalenessHint());
        return sb.toString();
    }

    private String buildUser(ExecutionContext ctx, Observations obs) {
        StringBuilder sb = new StringBuilder("Observations:\n");
        obs.items().forEach(o ->
            sb.append("[").append(o.origin()).append("][").append(o.trustTier()).append("] ")
              .append(o.content()).append("\n"));
        sb.append("\nDecide the next action.");
        return sb.toString();
    }
}
