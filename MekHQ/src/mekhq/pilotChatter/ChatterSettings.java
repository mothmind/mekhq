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

import java.util.function.UnaryOperator;

import mekhq.MHQOptions;

/**
 * Where pilot chatter comes from: any server that speaks the Anthropic Messages API. That is a local Ollama by
 * default, and Claude itself when pointed at {@code https://api.anthropic.com} with a key.
 *
 * @param enabled  whether pilots speak at all
 * @param endpoint the server's base address, without the {@code /v1/messages} path
 * @param model    the model the server should use
 * @param apiKey   the key to send; blank means the {@code ANTHROPIC_API_KEY} environment variable, or a placeholder
 */
public record ChatterSettings(boolean enabled, String endpoint, String model, String apiKey) {
    public static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "gemma4:e4b-it-qat";
    static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";
    static final String PLACEHOLDER_KEY = "ollama";

    public static ChatterSettings fromOptions(MHQOptions options) {
        return new ChatterSettings(options.getPilotChatterEnabled(), options.getPilotChatterEndpoint(),
              options.getPilotChatterModel(), options.getPilotChatterApiKey());
    }

    /**
     * @param environment reads an environment variable, returning {@code null} when it is not set
     *
     * @return the key to send: the one in settings, else the environment's Anthropic key, else a placeholder that
     *       local servers accept and ignore
     */
    String resolvedApiKey(UnaryOperator<String> environment) {
        if ((apiKey != null) && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String fromEnvironment = environment.apply(API_KEY_VARIABLE);
        if ((fromEnvironment != null) && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        return PLACEHOLDER_KEY;
    }

    /**
     * @return the full address of the Messages API on the configured server
     */
    String messagesUrl() {
        String base = ((endpoint == null) || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/messages";
    }
}
