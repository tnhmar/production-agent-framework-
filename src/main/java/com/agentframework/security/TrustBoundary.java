package com.agentframework.security;
import com.agentframework.foundation.TrustTier;
import java.util.Set;
/** Enforces trust-tier restrictions on cross-boundary data flow. */
public class TrustBoundary {
    private final String name;
    private final TrustTier minimumTrust;
    private final Set<String> allowedOrigins;

    public TrustBoundary(String name, TrustTier minimumTrust, Set<String> allowedOrigins) {
        this.name=name; this.minimumTrust=minimumTrust; this.allowedOrigins=allowedOrigins;
    }
    public boolean permits(TrustTier tier, String origin) {
        return tierOk(tier) && (allowedOrigins.isEmpty() || allowedOrigins.contains(origin));
    }
    private boolean tierOk(TrustTier t) {
        return switch (minimumTrust) {
            case HIGH   -> t == TrustTier.HIGH;
            case MEDIUM -> t == TrustTier.HIGH || t == TrustTier.MEDIUM;
            case LOW    -> t != TrustTier.UNTRUSTED;
            case UNTRUSTED -> true;
        };
    }
    public String name() { return name; }
    public TrustTier minimumTrust() { return minimumTrust; }
}
