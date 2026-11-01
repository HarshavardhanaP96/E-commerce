package com.sixpack.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Local stand-in for graph.facebook.com and www.google-analytics.com (JDK HttpServer, no extra dependency):
 * records every request and answers as scripted per path prefix. Unscripted paths succeed like the real APIs.
 */
final class ProviderStub {

    record Call(String method, String path, String query, String body) {
    }

    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private ProviderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    static ProviderStub start() {
        try {
            return new ProviderStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reply(String pathPrefix, Reply reply) {
        replies.put(pathPrefix, reply);
    }

    void reset() {
        calls.clear();
        replies.clear();
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> callsTo(String pathPrefix) {
        return calls.stream().filter(c -> c.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.entrySet().stream().filter(e -> path.startsWith(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElseGet(() -> path.startsWith("/debug/") ? Reply.of(200, "{\"validationMessages\":[]}")
                        : path.startsWith("/mp/") ? Reply.of(204, "")
                        : Reply.of(200, "{\"events_received\":1,\"messages\":[],\"fbtrace_id\":\"stub\"}"));
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach(exchange.getResponseHeaders()::add);
        try {
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(out);
                }
            }
        } catch (IOException ignored) {
            // the client gave up first (timeout tests)
        } finally {
            exchange.close();
        }
    }
}
