package com.agentframework.action;
import java.time.Duration;
public record OperationalParams(Duration timeout, int maxRetries, boolean idempotent, String idempotencyKeyField) {}
