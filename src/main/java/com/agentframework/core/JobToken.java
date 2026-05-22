package com.agentframework.core;
import java.time.Duration;
public record JobToken(String jobId, String statusEndpoint, Duration estimatedDuration) {}
