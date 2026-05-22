package com.agentframework.foundation;
import java.util.List;
public record FinalAnswer(String content, List<Citation> citations) implements Decision {}
