package com.agentframework.perception;
import com.agentframework.foundation.*;
import com.agentframework.perception.stages.*;
import java.time.Instant;
public class InputNormalizationPipeline {
    private final TypeIdentifier    typeId;
    private final FormatParser      parser;
    private final Normalizer        normalizer;
    private final MetadataInjector  injector;
    private final BudgetManager     budget;

    public InputNormalizationPipeline(TypeIdentifier typeId, FormatParser parser,
            Normalizer normalizer, MetadataInjector injector, BudgetManager budget) {
        this.typeId=typeId; this.parser=parser; this.normalizer=normalizer;
        this.injector=injector; this.budget=budget;
    }

    public static InputNormalizationPipeline defaults() {
        return new InputNormalizationPipeline(
            new DefaultTypeIdentifier(), new DefaultFormatParser(),
            new DefaultNormalizer(), new DefaultMetadataInjector(),
            new TokenBudgetManager(TokenEstimator.heuristic()));
    }

    public AnnotatedContent process(Object raw, InputOrigin origin, int tokenBudget) {
        InputType     type       = typeId.identify(raw);
        ParsedContent parsed     = parser.parse(raw, type);
        NormalizedContent normed = normalizer.normalize(parsed);
        AnnotatedContent  ann    = injector.injectMetadata(normed, origin);
        return budget.enforceTokenBudget(ann, tokenBudget);
    }
}
