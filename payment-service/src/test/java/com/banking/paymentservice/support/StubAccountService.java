package com.banking.paymentservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A fake account-service on a real TCP port (JDK built-in HTTP server, no extra dependency).
 *
 * <p>Because it's a real socket, the tests exercise the real HTTP client: real timeouts,
 * real JSON, real headers. Every request is recorded, so tests can assert what payment-service
 * sent (which calls, in what order, with which bearer token).
 */
public final class StubAccountService implements AutoCloseable {

    public record Recorded(String method, String path, String authorization, String body) {
        public String call() {
            return method + " " + path;
        }
    }

    private record Reply(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private StubAccountService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());   // slow replies don't block others
        server.createContext("/", this::handle);
        server.start();
    }

    public static StubAccountService start() {
        try {
            return new StubAccountService();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void reset() {
        replies.clear();
        requests.clear();
    }

    /** An account that exists for the caller; withdraw and deposit on it succeed by default. */
    public void account(UUID id, String currency, String status) {
        String json = """
                {"id":"%s","accountNumber":"1234567890","ownerId":"someone","type":"SAVINGS",
                 "currency":"%s","balance":1000.00,"status":"%s","createdAt":"2026-01-01T00:00:00Z"}"""
                .formatted(id, currency, status);
        reply("GET", "/api/accounts/" + id, 200, json);
        reply("POST", "/api/accounts/" + id + "/withdraw", 200, json);
        reply("POST", "/api/accounts/" + id + "/deposit", 200, json);
    }

    /** An RFC 9457 error like account-service's GlobalExceptionHandler produces. */
    public void problem(String method, String path, int status, String code, String detail) {
        String codeJson = code == null ? "" : ",\"code\":\"%s\"".formatted(code);
        reply(method, path, status, """
                {"type":"about:blank","title":"Error","status":%d,"detail":"%s"%s}""".formatted(status, detail, codeJson));
    }

    public void reply(String method, String path, int status, String body) {
        replies.put(method + " " + path, new Reply(status, body, Duration.ZERO));
    }

    /** Answers 200 only after {@code delay}, longer than the client's read timeout. */
    public void slowReply(String method, String path, Duration delay) {
        Reply current = replies.get(method + " " + path);
        replies.put(method + " " + path, new Reply(200, current == null ? "{}" : current.body(), delay));
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<String> calls() {
        return requests.stream().map(Recorded::call).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(method, path, exchange.getRequestHeaders().getFirst("Authorization"), body));

        Reply reply = replies.getOrDefault(method + " " + path, new Reply(404,
                "{\"title\":\"Resource not found\",\"status\":404,\"detail\":\"Account not found\"}", Duration.ZERO));
        if (!reply.delay().isZero()) {
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                reply.status() >= 400 ? "application/problem+json" : "application/json");
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException clientGone) {
            // The client already gave up (read timeout): exactly what the slow-reply tests want.
        } finally {
            exchange.close();
        }
    }
}
