package dev.gamersden.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * A terminal holding {@code GET /events} open, for suites that need to see what the floor was
 * told.
 *
 * <p>A real HTTP client on a real socket rather than a mock: the thing under test is that a
 * committed change reaches a subscriber over the wire, and half of that — the async dispatch, the
 * event framing, the JSON — lives outside the code a mocked hub would exercise.
 *
 * <p>Lines are pumped off the socket on a thread of their own and parsed into {@link Event}s as
 * the {@code event:} / {@code data:} pairs close. Comments (the opening {@code :connected} and the
 * keep-alive pings) carry no event and are skipped, which is exactly what a browser does with them.
 */
public final class SseClient implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How often {@link #await} looks again while it waits. */
    private static final long POLL_MILLIS = 20;

    private final List<Event> received = new CopyOnWriteArrayList<>();
    private final ExecutorService pump = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-client");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http;
    private final Stream<String> body;

    private SseClient(HttpClient http, Stream<String> body) {
        this.http = http;
        this.body = body;
        pump.submit(() -> parse(body));
    }

    /**
     * Subscribes and returns once the response headers are in — which, because the hub writes its
     * opening comment immediately, means the subscription is registered and anything published
     * from here on will be seen.
     */
    public static SseClient connect(String url, String accessToken) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GET " + url + " answered " + response.statusCode());
            }
            return new SseClient(http, response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("could not subscribe to " + url, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted subscribing to " + url, ex);
        }
    }

    // ---- assertions ------------------------------------------------------------------------

    /** Forgets everything seen so far, so the next {@link #await} is about the next action only. */
    public void clear() {
        received.clear();
    }

    /**
     * The first event of this name to arrive, waiting up to {@code timeout} for it.
     *
     * @throws AssertionError with everything that <em>did</em> arrive, which is what makes a
     *                        missing event debuggable
     */
    public Event await(String name, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Event found = received.stream()
                    .filter(event -> event.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                return found;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no %s event within %s — saw %s".formatted(name, timeout, names()));
    }

    /** Every event seen since the last {@link #clear}, in arrival order. */
    public List<Event> received() {
        return List.copyOf(received);
    }

    public List<String> names() {
        return received.stream().map(Event::name).toList();
    }

    /**
     * Gives the hub a moment to <em>not</em> send something. Only for the negative case; the
     * positive one waits on {@link #await} instead of sleeping.
     */
    public void settle() {
        sleep();
        sleep();
        sleep();
    }

    @Override
    public void close() {
        body.close();
        pump.shutdownNow();
        http.close();
    }

    // ---- the wire ---------------------------------------------------------------------------

    private void parse(Stream<String> lines) {
        String name = null;
        StringBuilder data = new StringBuilder();
        try {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isEmpty()) {
                    if (name != null) {
                        received.add(new Event(name, read(data.toString())));
                    }
                    name = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
                // Anything else is a comment or a retry hint — no event, nothing to collect.
            }
        } catch (RuntimeException closed) {
            // The stream ends when the test closes it. Not a failure.
        }
    }

    private static JsonNode read(String data) {
        try {
            return data.isEmpty() ? null : JSON.readTree(data);
        } catch (IOException ex) {
            throw new IllegalStateException("unparseable event payload: " + data, ex);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** One SSE event: the {@code event:} name and the parsed {@code data:} payload. */
    public record Event(String name, JsonNode data) {
    }
}
