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
 */
public final class JsonHttpClient {

    private static final String CONTENT_TYPE    = "Content-Type";
    private static final String MIME_JSON       = "application/json";
    private static final int    HTTP_OK_MIN     = 200;
    private static final int    HTTP_OK_MAX     = 299;
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

                if (resp.statusCode() < HTTP_OK_MIN || resp.statusCode() > HTTP_OK_MAX)
                    throw new RuntimeException(
                            "HTTP " + resp.statusCode() + " from " + url + ": " + resp.body());

                return mapper.readTree(resp.body());

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
     * Returns the parsed response body (may be empty object for 204 responses).
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
     * PUT {@code body} to {@code url} — used for idempotent upsert semantics
     * (e.g. Weaviate object update).
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
}
