package com.agentframework.memory;
import java.util.List;
public interface EntityExtractor {
    List<Triple> extract(String text, String provenance);
}
