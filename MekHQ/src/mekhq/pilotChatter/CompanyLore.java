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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import megamek.logging.MMLogger;

/**
 * The company's story, which its own pilots know and may bring up, and its public face, which is all anyone else
 * knows. The players write it in a Markdown file beside the campaign's saves and pilot journal:
 * <pre>
 * ## Our story
 * ...
 * ## Our public face
 * ...
 * </pre>
 * Anything outside those two sections, and anything inside {@code <!-- -->}, is ignored. It is read at the start of
 * every hosted battle, so an edit takes effect from the next one.
 *
 * @param story      what the company's own pilots know; empty if none is written
 * @param publicFace what allies and enemies know about the company; empty if none is written
 */
public record CompanyLore(String story, String publicFace) {
    private static final MMLogger LOGGER = MMLogger.create(CompanyLore.class);

    static final CompanyLore NONE = new CompanyLore("", "");
    static final int MAX_STORY = 1500;
    static final int MAX_PUBLIC_FACE = 400;
    static final String STORY = "our story";
    static final String PUBLIC_FACE = "our public face";

    /**
     * @return where a campaign keeps its lore: beside its saves and pilot journal, named for the campaign
     */
    public static Path fileFor(Path campaignsDirectory, String campaignName) {
        return campaignsDirectory.resolve(PilotJournal.safeName(campaignName) + " - company lore.md");
    }

    /**
     * @return the lore in the file, or none if there is no such file or it cannot be read
     */
    public static CompanyLore read(Path file) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.error(ex, "Could not read the company lore {}", file);
            return NONE;
        }
    }

    static CompanyLore parse(String markdown) {
        Map<String, StringBuilder> sections = new HashMap<>();
        StringBuilder current = null;
        for (String line : markdown.replaceAll("(?s)<!--.*?-->", "").split("\\R")) {
            if (line.matches("##\\s.*")) {
                current = sections.computeIfAbsent(line.substring(2).strip().toLowerCase(Locale.ROOT),
                      heading -> new StringBuilder());
            } else if (line.matches("#\\s.*")) {
                current = null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        return new CompanyLore(section(sections, STORY, MAX_STORY), section(sections, PUBLIC_FACE, MAX_PUBLIC_FACE));
    }

    private static String section(Map<String, StringBuilder> sections, String heading, int max) {
        StringBuilder text = sections.get(heading);
        return (text == null) ? "" : PilotDossier.abbreviate(PilotDossier.plain(text.toString()), max);
    }
}
