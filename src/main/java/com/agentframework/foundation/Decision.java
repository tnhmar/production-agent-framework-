package com.agentframework.foundation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
public sealed interface Decision
    permits ToolCall, ParallelToolCalls, FinalAnswer, Escalate, AskClarification {}
