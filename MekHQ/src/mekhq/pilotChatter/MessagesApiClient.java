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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Asks a model for one line of chatter over the Anthropic Messages API. Claude and Ollama both serve it, so the same
 * request works for either and only the settings differ.
 * <p>
 * Reasoning is switched off: a one-liner gains nothing from it, and Ollama otherwise lets reasoning models such as
 * Gemma 4 and Qwen 3.5 spend the whole token budget thinking and return no line at all.
 */
public class MessagesApiClient {
    static final String API_VERSION = "2023-06-01";
    static final int MAX_TOKENS = 80;
    static final int MAX_LINE_LENGTH = 240;
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504, 529);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final long RETRY_DELAY_MILLIS = 1000;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final String model;
    private final String apiKey;

    public MessagesApiClient(ChatterSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), System::getenv);
    }

    MessagesApiClient(ChatterSettings settings, HttpClient http, UnaryOperator<String> environment) {
        this.http = http;
        this.url = settings.messagesUrl();
        this.model = settings.model();
        this.apiKey = settings.resolvedApiKey(environment);
    }

    /**
     * @param system the standing instructions
     * @param prompt the request for this line
     *
     * @return the line, or empty if the model declined or said nothing usable
     *
     * @throws IOException if the server could not be reached, is not a usable address, or kept failing; the message
     *                     says why in a few words
     */
    public Optional<String> generate(String system, String prompt) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("content-type", "application/json")
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", API_VERSION)
                            .POST(HttpRequest.BodyPublishers.ofString(body(system, prompt)))
                            .build();
        } catch (IllegalArgumentException ex) {
            throw new IOException("not a usable server address: " + url, ex);
        }

        HttpResponse<String> response = send(request);
        if (RETRYABLE.contains(response.statusCode())) {
            pause();
            response = send(request);
        }
        if (response.statusCode() != 200) {
            throw new IOException(failure(response));
        }
        return parseLine(mapper.readTree(response.body()));
    }

    String body(String system, String prompt) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", system);
        body.put("temperature", 1.0);
        body.putObject("thinking").put("type", "disabled");
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return mapper.writeValueAsString(body);
    }

    /**
     * @return the line in a Messages API response, or empty for a refusal or a response with no usable text
     */
    static Optional<String> parseLine(JsonNode response) {
        if ("refusal".equals(response.path("stop_reason").asText())) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return cleanLine(text.toString());
    }

    /**
     * Reduces a model's reply to a single spoken line: the first non-blank line, without wrapping quotes or
     * emphasis, and never longer than a chat message should be.
     */
    static Optional<String> cleanLine(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String line = raw.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        String previous;
        do {
            previous = line;
            line = stripWrapping(line, "\"", "\"");
            line = stripWrapping(line, "“", "”");
            line = stripWrapping(line, "*", "*");
            line = stripWrapping(line, "'", "'");
        } while (!line.equals(previous));
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH - 1).strip() + "…";
        }
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
    }

    private static String stripWrapping(String line, String open, String close) {
        if ((line.length() > open.length() + close.length()) && line.startsWith(open) && line.endsWith(close)) {
            return line.substring(open.length(), line.length() - close.length()).strip();
        }
        return line;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the model", ex);
        } catch (IOException ex) {
            if (ex.getMessage() == null) {
                throw new IOException("could not connect (" + ex.getClass().getSimpleName() + ")", ex);
            }
            throw ex;
        }
    }

    /**
     * @return what went wrong, in a few words: the status, and the API's own error message when it gave one
     */
    private String failure(HttpResponse<String> response) {
        String message;
        try {
            message = mapper.readTree(response.body()).path("error").path("message").asText("");
        } catch (IOException | RuntimeException ex) {
            message = "";
        }
        return "HTTP " + response.statusCode() + (message.isBlank() ? "" : ": " + abbreviate(message));
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry", ex);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return (text.length() > 200) ? text.substring(0, 200) + "…" : text;
    }
}
