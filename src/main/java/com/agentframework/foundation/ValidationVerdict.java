package com.agentframework.foundation;
public final class ValidationVerdict {
    private final boolean passed;
    private final String  reason;
    private final boolean requiresApproval;
    private ValidationVerdict(boolean p, String r, boolean a){passed=p;reason=r;requiresApproval=a;}
    /** Factory: creates a passing verdict. */
    public static ValidationVerdict ok()               { return new ValidationVerdict(true,null,false); }
    /** @deprecated use ok() */
    public static ValidationVerdict passed()           { return ok(); }
    public static ValidationVerdict failed(String r)   { return new ValidationVerdict(false,r,false); }
    public static ValidationVerdict requireApproval(String r){ return new ValidationVerdict(false,r,true); }
    /** Returns true if this verdict allows the action to proceed. */
    public boolean isPassed()         { return passed; }
    public boolean requiresApproval() { return requiresApproval; }
    public String  reason()           { return reason; }
    @Override public String toString() {
        return passed ? "PASSED" : (requiresApproval ? "NEEDS_APPROVAL:" : "FAILED:") + reason;
    }
}
