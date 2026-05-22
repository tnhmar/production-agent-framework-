package com.agentframework.perception.stages;
public class DefaultNormalizer implements Normalizer {
    public NormalizedContent normalize(ParsedContent content) {
        String clean = content.textContent() == null ? "" :
            content.textContent().trim().replaceAll("\\s+", " ");
        return new NormalizedContent(clean, content.structured());
    }
}
