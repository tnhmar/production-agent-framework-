package com.agentframework.memory.impl;
import com.agentframework.memory.*;
import java.util.List;
/** No-op extractor for testing; returns empty triple list. */
public class PassThroughEntityExtractor implements EntityExtractor {
    public List<Triple> extract(String text, String provenance) { return List.of(); }
}
