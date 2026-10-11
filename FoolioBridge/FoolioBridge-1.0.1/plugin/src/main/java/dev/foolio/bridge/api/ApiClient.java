// FoolioBridge - Development by DeJIoVoy
package dev.foolio.bridge.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the site's FoolioBridge endpoint. Blocking; call it off the main thread.
 * Every request and every response is signed, so a forged or replayed answer is rejected.
 */
public final class ApiClient {

    public static final int PROTOCOL_VERSION = 1;
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final URI endpoint;
    private final String serverId;
    private final String pluginVersion;
    private final Signer signer;
    private final HttpClient http;
    private final Duration timeout;

    public ApiClient(String endpoint, String secret, String serverId, String pluginVersion, int timeoutSeconds) {
        this.endpoint = URI.create(endpoint);
        String scheme = this.endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("endpoint must be an http(s) URL");
        }
        this.serverId = serverId;
        this.pluginVersion = pluginVersion;
        this.signer = new Signer(secret);
        this.timeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isInsecureHttp() {
        String host = endpoint.getHost() == null ? "" : endpoint.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        return endpoint.getScheme().equalsIgnoreCase("http") && !local;
    }

    /** Checks connectivity and that both sides share the same secret. Returns the site's reply. */
    public Map<String, Object> ping() throws ApiException {
        return call(baseBody("ping"));
    }

    /** Orders waiting for delivery on this server. Malformed orders are skipped and reported to {@code warn}. */
    @SuppressWarnings("unchecked")
    public List<Order> fetchPending(java.util.function.Consumer<String> warn) throws ApiException {
        Map<String, Object> reply = call(baseBody("pending"));
        Object raw = reply.get("orders");
        List<Order> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List)) throw new ApiException("'orders' is not an array");
        for (Object o : (List<Object>) raw) {
            try {
                out.add(Order.fromJson(o));
            } catch (IllegalArgumentException e) {
                warn.accept("skipped malformed order: " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Reports delivery results. Returns the ids the site confirmed; if the site
     * doesn't list them, all sent ids are considered accepted.
     */
    @SuppressWarnings("unchecked")
    public List<String> ack(List<Map<String, Object>> results) throws ApiException {
        Map<String, Object> body = baseBody("ack");
        body.put("results", results);
        Map<String, Object> reply = call(body);
        List<String> accepted = new ArrayList<>();
        Object raw = reply.get("accepted");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) accepted.add(String.valueOf(o));
        } else {
            for (Map<String, Object> r : results) accepted.add(String.valueOf(r.get("id")));
        }
        return accepted;
    }

    private Map<String, Object> baseBody(String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", PROTOCOL_VERSION);
        m.put("action", action);
        m.put("server", serverId);
        m.put("plugin", pluginVersion);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Map<String, Object> bodyMap) throws ApiException {
        String body = Json.write(bodyMap);
        long ts = System.currentTimeMillis() / 1000L;
        String nonce = Signer.newNonce();
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "FoolioBridge/" + pluginVersion)
                .header("X-Foolio-Timestamp", Long.toString(ts))
                .header("X-Foolio-Nonce", nonce)
                .header("X-Foolio-Signature", signer.sign(Signer.REQ, ts, nonce, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException("site unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted", e);
        }

        String text = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new ApiException("site rejected the signature (HTTP " + resp.statusCode()
                    + ") - check that the secret matches and the server clock is correct");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ApiException("site answered HTTP " + resp.statusCode() + ": " + shorten(text));
        }

        String tsHeader = resp.headers().firstValue("X-Foolio-Timestamp").orElse(null);
        String sigHeader = resp.headers().firstValue("X-Foolio-Signature").orElse(null);
        if (tsHeader == null || sigHeader == null) {
            throw new ApiException("response is not signed - is the endpoint URL correct?");
        }
        long rts;
        try {
            rts = Long.parseLong(tsHeader.trim());
        } catch (NumberFormatException e) {
            throw new ApiException("bad response timestamp");
        }
        if (Math.abs(rts - ts) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ApiException("response timestamp too far from local clock");
        }
        if (!signer.verify(Signer.RES, rts, nonce, text, sigHeader)) {
            throw new ApiException("response signature is invalid - the answer was NOT trusted");
        }

        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ApiException("response is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) throw new ApiException("response is not a JSON object");
        Map<String, Object> reply = (Map<String, Object>) parsed;
        if (Boolean.FALSE.equals(reply.get("ok"))) {
            throw new ApiException("site error: " + reply.get("error"));
        }
        return reply;
    }

    private static String shorten(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public static final class ApiException extends Exception {
        public ApiException(String msg) { super(msg); }
        public ApiException(String msg, Throwable cause) { super(msg, cause); }
    }
}
