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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.annotations.Nullable;
import megamek.logging.MMLogger;

/**
 * Everything the pilots have said, one JSON object per line, kept next to the campaign's saves so it travels with
 * them. The pilots' recent lines go back into their prompts, which is what gives them continuity: callbacks, running
 * jokes, and no repeating themselves.
 */
public class PilotJournal {
    private static final MMLogger LOGGER = MMLogger.create(PilotJournal.class);

    /**
     * One spoken line.
     *
     * @param time         when it was said, in real time
     * @param campaignDate the in-game date
     * @param battle       the battle it was said in
     * @param round        the game round
     * @param speakerKey   who said it, as {@link PilotDossier#key()}
     * @param speaker      the name shown in chat
     * @param event        what prompted it
     * @param line         what they said
     * @param team         the speaker's team in that battle, so a later speaker knows which side said it; 0 if unknown
     * @param pronouns     the speaker's pronouns, so a later speaker refers to them rightly; blank if unknown
     */
    public record Entry(String time, String campaignDate, String battle, int round, String speakerKey,
          String speaker, String event, String line, int team, String pronouns, @Nullable List<Integer> heardBy) {}

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Opens a journal, reading whatever it already holds. A journal that does not exist yet is created on the first
     * line; lines it cannot read are skipped.
     */
    public PilotJournal(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        entries.add(mapper.readValue(line, Entry.class));
                    } catch (IOException ex) {
                        LOGGER.warn("Skipping an unreadable pilot journal line in {}", file);
                    }
                }
            } catch (IOException ex) {
                LOGGER.error(ex, "Could not read the pilot journal {}", file);
            }
        }
    }

    public synchronized void append(Entry entry) {
        entries.add(entry);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(mapper.writeValueAsString(entry));
                writer.newLine();
            }
        } catch (IOException ex) {
            LOGGER.error(ex, "Could not write to the pilot journal {}", file);
        }
    }

    /**
     * @return the speaker's last {@code count} lines, oldest first
     */
    public List<Entry> recentBy(String speakerKey, int count) {
        return recentBy(speakerKey, count, entry -> true);
    }

    /**
     * @return the speaker's last {@code count} lines that pass the filter, oldest first
     */
    public synchronized List<Entry> recentBy(String speakerKey, int count, Predicate<Entry> filter) {
        return last(entries.stream().filter(entry -> entry.speakerKey().equals(speakerKey)).filter(filter).toList(),
              count);
    }

    /**
     * @return the last {@code count} lines anyone said in the battle, oldest first
     */
    public List<Entry> recentInBattle(String battle, int count) {
        return recentInBattle(battle, count, entry -> true);
    }

    /**
     * @return the last {@code count} lines anyone said in the battle that pass the filter, oldest first
     */
    public synchronized List<Entry> recentInBattle(String battle, int count, Predicate<Entry> filter) {
        return last(entries.stream().filter(entry -> entry.battle().equals(battle)).filter(filter).toList(), count);
    }

    private static List<Entry> last(List<Entry> list, int count) {
        return list.subList(Math.max(0, list.size() - count), list.size());
    }

    /**
     * @return where a campaign keeps its pilot journal: beside its saves, named for the campaign
     */
    public static Path fileFor(Path campaignsDirectory, String campaignName) {
        return campaignsDirectory.resolve(safeName(campaignName) + " - pilot journal.jsonl");
    }

    /**
     * @return the campaign's name, made safe to use in a file name
     */
    static String safeName(String campaignName) {
        String safeName = campaignName.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return safeName.isEmpty() ? "campaign" : safeName;
    }
}
