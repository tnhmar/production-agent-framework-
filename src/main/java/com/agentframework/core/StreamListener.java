package com.agentframework.core;

import com.agentframework.foundation.FinalAnswer;

/**
 * Listener for streaming final answers token-by-token.
 */
public interface StreamListener {

    /** Called for each token as it arrives from the LLM. */
    void onToken(String taskId, String token);

    /** Called once when the stream completes normally. */
    void onComplete(String taskId, FinalAnswer answer);

    /** Called if the stream fails mid-way. */
    void onError(String taskId, Throwable cause);
}
