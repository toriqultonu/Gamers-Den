package dev.gamersden.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The cloud mirror, for a test: a real HTTP server on a real port that answers
 * {@code POST /api/v1/sync/push}, checks the {@code X-Sync-Token} and remembers what it was given.
 *
 * <p>A stub rather than a second Spring context on purpose. What the release gate has to prove is
 * that the <em>venue</em> loses nothing when the far end goes away, so the far end needs to be
 * something the test can actually kill and bring back — a socket that stops accepting, which is
 * what a cafe with a dead uplink meets. The JDK's own server does that in a few lines and pulls in
 * no dependency.
 *
 * <p>It also behaves like the real receiver in the one way that matters: ops are stored by
 * {@code opId} and a second copy of one is counted as a duplicate rather than as a second sale.
 */
public final class FakeCloud implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final String token;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final Set<String> opIds = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile boolean down;
    private volatile int pushCalls;
    private volatile int duplicates;

    private FakeCloud(HttpServer server, String token) {
        this.server = server;
        this.token = token;
    }

    /** Starts on an ephemeral port. */
    public static FakeCloud start(String token) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeCloud cloud = new FakeCloud(server, token);
            server.createContext("/api/v1/sync/push", cloud::handlePush);
            server.setExecutor(null);
            server.start();
            return cloud;
        } catch (IOException cannotBind) {
            throw new IllegalStateException("could not start the fake cloud", cannotBind);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * The uplink drops.
     *
     * <p>Refusing with a 503 rather than closing the socket, and the distinction does not matter:
     * the pusher catches every {@code RuntimeException} the same way, because from the venue's
     * side a cloud that refuses, times out, resets or answers 500 are one situation — the batch is
     * not stamped and the same ops are offered again next tick. A 503 is the deterministic way to
     * say that; a closed port would say it too, and would leave the test guessing whether the
     * operating system will hand the number back on reconnect.
     */
    public void goOffline() {
        down = true;
    }

    public void comeBackOnline() {
        down = false;
    }

    /** Every op this cloud holds, in the order it first saw them. */
    public List<JsonNode> ops() {
        return List.copyOf(received);
    }

    public List<String> opIdsInOrder() {
        return received.stream().map(op -> op.get("opId").asText()).toList();
    }

    /** The ops of one aggregate, in arrival order. */
    public List<JsonNode> opsOf(String aggregate) {
        return received.stream()
                .filter(op -> aggregate.equals(op.path("aggregate").asText()))
                .toList();
    }

    /** The {@code type} values of one aggregate — what the money path did, in order. */
    public List<String> typesOf(String aggregate) {
        return opsOf(aggregate).stream().map(op -> op.path("type").asText()).toList();
    }

    public int pushCalls() {
        return pushCalls;
    }

    /** Ops offered a second time — the count that proves a re-push is not a second sale. */
    public int duplicates() {
        return duplicates;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- the endpoint ------------------------------------------------------------------------

    private void handlePush(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try (exchange) {
            pushCalls++;
            if (down) {
                respond(exchange, 503, "{\"error\":\"offline\"}");
                return;
            }
            if (!token.equals(exchange.getRequestHeaders().getFirst("X-Sync-Token"))) {
                respond(exchange, 401, "{\"error\":\"bad token\"}");
                return;
            }
            JsonNode body;
            try (InputStream in = exchange.getRequestBody()) {
                body = JSON.readTree(in.readAllBytes());
            }
            int accepted = 0;
            List<JsonNode> batch = new ArrayList<>();
            body.path("ops").forEach(batch::add);
            for (JsonNode op : batch) {
                if (opIds.add(op.get("opId").asText())) {
                    received.add(op);
                    accepted++;
                } else {
                    duplicates++;
                }
            }
            respond(exchange, 200, "{\"accepted\":%d,\"duplicates\":%d}"
                    .formatted(accepted, batch.size() - accepted));
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
