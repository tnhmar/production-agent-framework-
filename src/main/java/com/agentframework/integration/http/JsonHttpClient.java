package com.agentframework.integration.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin JSON-over-HTTP client used by all integration adapters.
 * Auth is injected via {@link AuthStrategy} — this class has zero knowledge
 * of which provider it is serving.
 *
 * <h3>M2 fix — non-retryable 4xx fast-fail</h3>
 * <p>Previously the retry loop retried ALL non-2xx responses, including HTTP
 * 400 Bad Request, 401 Unauthorized, and 403 Forbidden.  These are
 * deterministic errors — retrying cannot succeed without changing the request.
 * The fix introduces {@link NonRetryableHttpException} and a fast-fail check:
 * 4xx responses (except 429 Too Many Requests) throw immediately without
 * consuming any retry budget.
 *
 * <h3>Retry policy</h3>
 * <table>
 *   <tr><th>Status</th><th>Behaviour</th></tr>
 *   <tr><td>2xx</td><td>Success — return parsed body</td></tr>
 *   <tr><td>4xx except 429</td><td>{@link NonRetryableHttpException} — fail immediately</td></tr>
 *   <tr><td>429</td><td>Retry with exponential back-off</td></tr>
 *   <tr><td>5xx</td><td>Retry with exponential back-off</td></tr>
 *   <tr><td>Network error</td><td>Retry with exponential back-off</td></tr>
 * </table>
 */
public final class JsonHttpClient {

    private static final String CONTENT_TYPE    = "Content-Type";
    private static final String MIME_JSON       = "application/json";
    private static final int    HTTP_OK_MIN     = 200;
    private static final int    HTTP_OK_MAX     = 299;
    private static final int    HTTP_TOO_MANY   = 429;
    private static final int    HTTP_4XX_MIN    = 400;
    private static final int    HTTP_4XX_MAX    = 499;
    private static final long   RETRY_BASE_MS   = 500L;

    private final HttpClient     http;
    private final ObjectMapper   mapper;
    private final int            timeoutSeconds;
    private final int            maxRetries;

    public JsonHttpClient(ConnectionPolicy policy) {
        this.timeoutSeconds = policy.timeoutSeconds();
        this.maxRetries     = policy.maxRetries();
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.mapper = new ObjectMapper();
    }

    /** Creates a fresh {@link ObjectNode} for building request bodies. */
    public ObjectNode newObject() {
        return mapper.createObjectNode();
    }

    /**
     * POST {@code body} to {@code url} using the given {@link AuthStrategy},
     * retrying up to {@code maxRetries} times with exponential back-off.
     *
     * <p>4xx responses (except 429) throw {@link NonRetryableHttpException}
     * immediately without consuming any retry budget (M2 fix).
     */
    public JsonNode post(String url, ObjectNode body, AuthStrategy auth) {
        String json = body.toString();
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0)
                    Thread.sleep(RETRY_BASE_MS * (1L << (attempt - 1)));

                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header(CONTENT_TYPE, MIME_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(json));
                auth.apply(rb);

                HttpResponse<String> resp =
                        http.send(rb.build(), HttpResponse.BodyHandlers.ofString());

                int status = resp.statusCode();

                // M2 fix: deterministic client errors — fail immediately, no retry.
                if (status >= HTTP_4XX_MIN && status <= HTTP_4XX_MAX && status != HTTP_TOO_MANY) {
                    throw new NonRetryableHttpException(status, url, resp.body());
                }

                if (status < HTTP_OK_MIN || status > HTTP_OK_MAX)
                    throw new RuntimeException(
                            "HTTP " + status + " from " + url + ": " + resp.body());

                return mapper.readTree(resp.body());

            } catch (NonRetryableHttpException nre) {
                throw nre;  // propagate immediately — do not retry
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during retry back-off", ie);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new RuntimeException(
                "All " + (maxRetries + 1) + " attempts failed for POST " + url, last);
    }

    /**
     * DELETE to {@code url} with the given auth strategy.
     */
    public JsonNode delete(String url, ObjectNode body, AuthStrategy auth) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header(CONTENT_TYPE, MIME_JSON)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString()));
            auth.apply(rb);

            HttpResponse<String> resp =
                    http.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < HTTP_OK_MIN || resp.statusCode() > HTTP_OK_MAX)
                throw new RuntimeException(
                        "HTTP " + resp.statusCode() + " from DELETE " + url
                                + ": " + resp.body());

            String responseBody = resp.body();
            return responseBody == null || responseBody.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(responseBody);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during DELETE", ie);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("DELETE " + url + " failed", e);
        }
    }

    /**
     * GET {@code url} with the given auth strategy.
     */
    public JsonNode get(String url, AuthStrategy auth) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
            auth.apply(rb);

            HttpResponse<String> resp =
                    http.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < HTTP_OK_MIN || resp.statusCode() > HTTP_OK_MAX)
                throw new RuntimeException(
                        "HTTP " + resp.statusCode() + " from GET " + url + ": " + resp.body());

            return mapper.readTree(resp.body());

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during GET", ie);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("GET " + url + " failed", e);
        }
    }

    /**
     * PUT {@code body} to {@code url} — used for idempotent upsert semantics.
     */
    public JsonNode put(String url, ObjectNode body, AuthStrategy auth) {
        String json = body.toString();
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header(CONTENT_TYPE, MIME_JSON)
                    .PUT(HttpRequest.BodyPublishers.ofString(json));
            auth.apply(rb);

            HttpResponse<String> resp =
                    http.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < HTTP_OK_MIN || resp.statusCode() > HTTP_OK_MAX)
                throw new RuntimeException(
                        "HTTP " + resp.statusCode() + " from PUT " + url + ": " + resp.body());

            String responseBody = resp.body();
            return responseBody == null || responseBody.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(responseBody);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during PUT", ie);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("PUT " + url + " failed", e);
        }
    }

    // ── M2 fix: NonRetryableHttpException ────────────────────────────────────

    /**
     * Thrown when an HTTP 4xx response (except 429) is received.
     * These errors are deterministic — retrying cannot succeed without
     * changing the request itself.  Callers should inspect
     * {@link #statusCode()}, {@link #url()}, and {@link #responseBody()}
     * for diagnostics.
     */
    public static final class NonRetryableHttpException extends RuntimeException {

        private final int    statusCode;
        private final String url;
        private final String responseBody;

        public NonRetryableHttpException(int statusCode, String url, String responseBody) {
            super("HTTP " + statusCode + " (non-retryable) from " + url + ": " + responseBody);
            this.statusCode   = statusCode;
            this.url          = url;
            this.responseBody = responseBody;
        }

        public int    statusCode()   { return statusCode;   }
        public String url()          { return url;          }
        public String responseBody() { return responseBody; }
    }
}
