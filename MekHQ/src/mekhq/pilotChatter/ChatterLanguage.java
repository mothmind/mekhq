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

import java.util.Map;

import megamek.common.annotations.Nullable;
import mekhq.campaign.universe.Faction;

/**
 * Which pilots speak their faction's own language instead of English. Most of the Inner Sphere talks English over
 * comms, but some factions keep a language of their own (Complete Classic Universe): the Draconis Combine Japanese, the
 * Capellan Confederation and St. Ives Chinese, Rasalhague Swedish, the Lyrans German, the Marian Hegemony Latin.
 * About three in ten pilots from one of those speak it - fixed per pilot, so the same pilots always do - and they use
 * it in about three lines in five, falling back on English for the rest.
 */
final class ChatterLanguage {
    /** The share of pilots from a faction with its own language who speak it: a minority. */
    static final int SPEAKS_OWN_LANGUAGE_PERCENT = 30;
    /** The share of those pilots' lines spoken in it rather than English. */
    static final int OWN_LANGUAGE_LINE_PERCENT = 60;

    private static final Map<String, String> BY_FACTION = Map.of(
          "DC", "Japanese",
          "CC", "Mandarin Chinese",
          "SIC", "Mandarin Chinese",
          "LA", "German",
          "FRR", "Swedish",
          "RD", "Swedish",
          "MH", "Latin");

    private ChatterLanguage() {}

    /**
     * @return the faction's own language, or {@code null} if it speaks English or is unknown
     */
    static @Nullable String of(@Nullable Faction faction) {
        return (faction == null) ? null : BY_FACTION.get(faction.getShortName());
    }

    /**
     * @param pilotKey the pilot's stable key, from {@link PilotDossier#key()}
     *
     * @return whether this pilot speaks their faction's language; the same answer every time for the same pilot
     */
    static boolean speaksOwnLanguage(String pilotKey) {
        return Math.floorMod(pilotKey.hashCode(), 100) < SPEAKS_OWN_LANGUAGE_PERCENT;
    }

    /**
     * @param pilotKey        the pilot's stable key
     * @param factionLanguage their faction's own language, or {@code null} if it speaks English
     * @param lineRoll        a roll from 0 to 99 for this line
     *
     * @return the instruction to speak that language this line, or an empty string if the line is in English
     */
    static String forPilot(String pilotKey, @Nullable String factionLanguage, int lineRoll) {
        if ((factionLanguage == null) || !speaksOwnLanguage(pilotKey) || (lineRoll >= OWN_LANGUAGE_LINE_PERCENT)) {
            return "";
        }
        return "This pilot prefers to speak " + factionLanguage + ". Write the line in " + factionLanguage
                     + ", in the Latin alphabet, then a short English translation in brackets. The 20-word limit "
                     + "counts the " + factionLanguage + " only.";
    }
}
