/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 */
package mekhq.pilotChatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the one HTTP client both brains share, against a stand-in Messages API server.
 */
class MessagesApiClientTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<Map<String, List<String>>> headers = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<Integer> statuses = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int call = calls.getAndIncrement();
            paths.add(exchange.getRequestURI().getPath());
            headers.add(Map.copyOf(exchange.getRequestHeaders()));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses.get(Math.min(call, statuses.size() - 1));
            byte[] reply = replies.get(Math.min(call, replies.size() - 1)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private MessagesApiClient client(String apiKey) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new MessagesApiClient(new ChatterSettings(true, endpoint, "gemma4:e4b-it-qat", apiKey),
              HttpClient.newHttpClient(), name -> null);
    }

    private void answer(int status, String reply) {
        statuses.add(status);
        replies.add(reply);
    }

    private static String textReply(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\","
                     + "\"content\":[{\"type\":\"text\",\"text\":" + new ObjectMapper().valueToTree(text) + "}]}";
    }

    @Test
    @DisplayName("sends a Messages API request with the configured model, the prompt and the right headers")
    void sendsAMessagesRequest() throws IOException {
        answer(200, textReply("Lock and load, Hatchet's on the line."));

        Optional<String> line = client("").generate("be a pilot", "say something");

        assertEquals(Optional.of("Lock and load, Hatchet's on the line."), line);
        assertEquals("/v1/messages", paths.getFirst(), "a trailing slash on the endpoint is not doubled");
        assertEquals(List.of("ollama"), headers.getFirst().get("X-api-key"), "a blank key sends the placeholder");
        assertEquals(List.of(MessagesApiClient.API_VERSION), headers.getFirst().get("Anthropic-version"));

        JsonNode body = new ObjectMapper().readTree(bodies.getFirst());
        assertEquals("gemma4:e4b-it-qat", body.path("model").asText());
        assertEquals(MessagesApiClient.MAX_TOKENS, body.path("max_tokens").asInt());
        assertEquals("be a pilot", body.path("system").asText());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("say something", body.path("messages").get(0).path("content").asText());
        assertTrue(body.path("stop_sequences").isMissingNode(), "whitespace stop sequences are rejected by Claude");
        assertEquals("disabled", body.path("thinking").path("type").asText(),
              "without this, reasoning models spend every token thinking and say nothing");
    }

    @Test
    @DisplayName("a configured key is sent as the API key")
    void sendsTheConfiguredKey() throws IOException {
        answer(200, textReply("Copy that."));

        client("sk-ant-test").generate("s", "p");

        assertEquals(List.of("sk-ant-test"), headers.getFirst().get("X-api-key"));
    }

    @Test
    @DisplayName("a refusal is no line at all")
    void refusalIsNoLine() throws IOException {
        answer(200, "{\"stop_reason\":\"refusal\",\"content\":[]}");

        assertEquals(Optional.empty(), client("").generate("s", "p"));
    }

    @Test
    @DisplayName("a busy server is tried once more before giving up")
    void retriesOnceWhenBusy() throws IOException {
        answer(503, "{\"type\":\"error\"}");
        answer(200, textReply("Back in the fight."));

        assertEquals(Optional.of("Back in the fight."), client("").generate("s", "p"));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("a server that keeps failing is reported as a failure")
    void keepsFailing() {
        answer(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");

        assertThrows(IOException.class, () -> client("").generate("s", "p"));
        assertEquals(2, calls.get(), "one retry, then give up");
    }

    @Test
    @DisplayName("a bad request is not retried")
    void badRequestIsNotRetried() {
        answer(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}");

        assertThrows(IOException.class, () -> client("").generate("s", "p"));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("nobody listening is reported as a failure")
    void nobodyListening() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MessagesApiClient client = new MessagesApiClient(
              new ChatterSettings(true, "http://127.0.0.1:" + port, "m", ""), HttpClient.newHttpClient(),
              name -> null);

        assertEquals("could not connect (ConnectException)",
              assertThrows(IOException.class, () -> client.generate("s", "p")).getMessage());
    }

    @Test
    @DisplayName("an address that is not a URL is a failure to reach the server, not a crash")
    void unusableAddress() {
        for (String endpoint : List.of("localhost:11434", "not a server at all")) {
            MessagesApiClient client = new MessagesApiClient(new ChatterSettings(true, endpoint, "m", ""),
                  HttpClient.newHttpClient(), name -> null);

            IOException failure = assertThrows(IOException.class, () -> client.generate("s", "p"));
            assertTrue(failure.getMessage().startsWith("not a usable server address"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("a failure says why in a few words: the status, and the API's own message if it gave one")
    void failuresSayWhy() {
        answer(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                          + "\"message\":\"invalid x-api-key\"}}");
        assertEquals("HTTP 401: invalid x-api-key",
              assertThrows(IOException.class, () -> client("").generate("s", "p")).getMessage());

        answer(405, "<!doctype html><html><body>Example Domain</body></html>");
        assertEquals("HTTP 405", assertThrows(IOException.class, () -> client("").generate("s", "p")).getMessage(),
              "a web page is not an explanation");
    }

    @Test
    @DisplayName("a reply is cut down to one clean spoken line")
    void cleansTheLine() {
        assertEquals(Optional.of("Eat laser, Kurita!"), MessagesApiClient.cleanLine("\"Eat laser, Kurita!\""));
        assertEquals(Optional.of("Eat laser"), MessagesApiClient.cleanLine("*\"Eat laser\"*"));
        assertEquals(Optional.of("First line"), MessagesApiClient.cleanLine("\n  First line\nSecond line"));
        assertEquals(Optional.of("It's a trap"), MessagesApiClient.cleanLine("It's a trap"),
              "an apostrophe inside the line is not a wrapping quote");
        assertEquals(Optional.empty(), MessagesApiClient.cleanLine("   \n "));
        assertEquals(MessagesApiClient.MAX_LINE_LENGTH,
              MessagesApiClient.cleanLine("x".repeat(500)).orElseThrow().length());
    }

    @Test
    @DisplayName("the key comes from settings, else the environment, else a placeholder")
    void keyResolution() {
        ChatterSettings blank = new ChatterSettings(true, "", "m", " ");
        assertEquals("from-env", blank.resolvedApiKey(name -> "ANTHROPIC_API_KEY".equals(name) ? "from-env" : null));
        assertEquals("ollama", blank.resolvedApiKey(name -> null));
        assertEquals("typed", new ChatterSettings(true, "", "m", "typed").resolvedApiKey(name -> "from-env"));
        assertEquals("http://localhost:11434/v1/messages", blank.messagesUrl(), "a blank endpoint is the default");
    }
}
