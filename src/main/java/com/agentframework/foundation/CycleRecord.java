package com.agentframework.foundation;
public record CycleRecord(
        int          cycleNumber,
        Observations observations,
        Decision     decision,
        ActionResult result,
        String       reviewSummary) {}
