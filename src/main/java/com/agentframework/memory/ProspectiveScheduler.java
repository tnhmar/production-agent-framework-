package com.agentframework.memory;
import com.agentframework.core.ExecutionContext;
import java.time.Duration; import java.time.Instant; import java.util.List;
public interface ProspectiveScheduler {
    void schedule(ProspectiveRecord record);
    List<ProspectiveRecord> getDue(Instant now, Duration sessionIdleTime, ExecutionContext ctx);
    void cancel(String recordId);
    List<ProspectiveRecord> pendingForUser(String userId);
}
