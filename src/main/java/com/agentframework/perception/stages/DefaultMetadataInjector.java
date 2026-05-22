package com.agentframework.perception.stages;
import com.agentframework.foundation.*;
import java.time.Instant;
public class DefaultMetadataInjector implements MetadataInjector {
    public AnnotatedContent injectMetadata(NormalizedContent content, InputOrigin origin) {
        TrustTier tier = switch (origin) {
            case USER, SYSTEM -> TrustTier.HIGH;
            case TOOL, MEMORY -> TrustTier.MEDIUM;
            case EXTERNAL     -> TrustTier.LOW;
        };
        return new AnnotatedContent(content, origin, tier, Instant.now(), origin.name());
    }
}
