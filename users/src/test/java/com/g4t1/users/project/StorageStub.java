package com.g4t1.users.project;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// A real HTTP server on a random port standing in for Storage Management; it records every request
final class StorageStub {

    record Request(String method, String path, String query, String authorization) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    StorageStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return requests;
    }

    void reset() {
        requests.clear();
    }

    void stop() {
        server.stop(0);
    }
}
