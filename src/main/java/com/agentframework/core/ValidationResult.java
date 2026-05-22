package com.agentframework.core;
import com.agentframework.foundation.Decision;
import java.util.List;
public sealed interface ValidationResult
    permits ValidationResult.Passed, ValidationResult.Failed, ValidationResult.NeedsCorrection {
    record Passed()                                                implements ValidationResult {}
    record Failed(String reason, List<String> details)             implements ValidationResult {}
    record NeedsCorrection(String reason, Decision suggestedDecision) implements ValidationResult {}
}
