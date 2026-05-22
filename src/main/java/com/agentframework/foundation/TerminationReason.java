package com.agentframework.foundation;
public sealed interface TerminationReason
    permits TerminationReason.GoalCompleted, TerminationReason.Escalated,
            TerminationReason.ResourceLimit,  TerminationReason.FailureEscalation {
    record GoalCompleted()                implements TerminationReason {}
    record Escalated(String reason)       implements TerminationReason {}
    record ResourceLimit(String detail)   implements TerminationReason {}
    record FailureEscalation(String detail) implements TerminationReason {}
}
