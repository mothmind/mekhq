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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import mekhq.campaign.universe.Faction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * About three in ten pilots from a faction with its own language speak it - always the same pilots - and they
 * use it in about three lines in five.
 */
class ChatterLanguageTest {

    @Test
    @DisplayName("factions with their own language map to it; English-speaking and unknown ones do not")
    void factionLanguages() {
        assertEquals("Japanese", ChatterLanguage.of(new Faction("DC", "Draconis Combine")));
        assertEquals("Mandarin Chinese", ChatterLanguage.of(new Faction("CC", "Capellan Confederation")));
        assertEquals("German", ChatterLanguage.of(new Faction("LA", "Lyran Alliance")));
        assertEquals("Swedish", ChatterLanguage.of(new Faction("FRR", "Free Rasalhague Republic")));
        assertEquals("Latin", ChatterLanguage.of(new Faction("MH", "Marian Hegemony")));
        assertNull(ChatterLanguage.of(new Faction("FS", "Federated Suns")));
        assertNull(ChatterLanguage.of(null));
    }

    @Test
    @DisplayName("only a minority, about 30% of pilots, speak their faction's language")
    void aboutThirtyPercentOfPilotsSpeakIt() {
        int speakers = 0;
        int pilots = 5000;
        for (int i = 0; i < pilots; i++) {
            if (ChatterLanguage.speaksOwnLanguage("person:" + UUID.randomUUID())) {
                speakers++;
            }
        }
        double share = (100.0 * speakers) / pilots;
        assertTrue((share > 25) && (share < 35), "expected about 30% to speak it, got " + share + "%");
    }

    @Test
    @DisplayName("a speaker uses their language for rolls under 60, so about three lines in five")
    void aboutSixtyPercentOfTheirLines() {
        String speaker = keyThatPrefers(true);
        assertTrue(ChatterLanguage.forPilot(speaker, "German", 0).contains("speak German"));
        assertTrue(ChatterLanguage.forPilot(speaker, "German", 59).contains("speak German"));
        assertEquals("", ChatterLanguage.forPilot(speaker, "German", 60), "a roll of 60 or more is an English line");
        assertEquals("", ChatterLanguage.forPilot(speaker, "German", 99));
    }

    @Test
    @DisplayName("a pilot's choice never changes")
    void choiceIsStable() {
        String key = "person:" + UUID.randomUUID();
        boolean first = ChatterLanguage.speaksOwnLanguage(key);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, ChatterLanguage.speaksOwnLanguage(key));
        }
    }

    @Test
    @DisplayName("the instruction names the language, asks for Latin script and an English translation")
    void instruction() {
        String key = keyThatPrefers(true);
        String line = ChatterLanguage.forPilot(key, "Japanese", 0);
        assertTrue(line.contains("speak Japanese") && line.contains("Latin alphabet")
                         && line.contains("English translation"), line);
        assertEquals("", ChatterLanguage.forPilot(keyThatPrefers(false), "Japanese", 0), "this pilot speaks English");
        assertEquals("", ChatterLanguage.forPilot(key, null, 0), "an English-speaking faction gets no instruction");
    }

    private static String keyThatPrefers(boolean prefers) {
        while (true) {
            String key = "person:" + UUID.randomUUID();
            if (ChatterLanguage.speaksOwnLanguage(key) == prefers) {
                return key;
            }
        }
    }
}
