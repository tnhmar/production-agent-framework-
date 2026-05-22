package com.agentframework.memory;
import java.time.Duration; import java.time.Instant;
public sealed interface Trigger
    permits Trigger.TimeBased, Trigger.IdleBased, Trigger.ConditionBased, Trigger.SessionStart {
    record TimeBased(Instant targetTime)      implements Trigger {}
    record IdleBased(Duration idleDuration)   implements Trigger {}
    record ConditionBased(String condition)   implements Trigger {}
    record SessionStart()                     implements Trigger {}
}
