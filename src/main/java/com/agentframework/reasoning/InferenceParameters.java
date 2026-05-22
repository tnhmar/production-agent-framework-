package com.agentframework.reasoning;
import java.util.List;
public record InferenceParameters(double temperature, int maxTokens,
        List<String> stopSequences, boolean stream) {
    public static InferenceParameters defaults() {
        return new InferenceParameters(0.2, 4096, List.of(), false);
    }
    public InferenceParameters withTemperature(double t) {
        return new InferenceParameters(t, maxTokens, stopSequences, stream);
    }
    public InferenceParameters withMaxTokens(int t) {
        return new InferenceParameters(temperature, t, stopSequences, stream);
    }
}
