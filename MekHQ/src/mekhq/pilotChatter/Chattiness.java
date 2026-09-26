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

/**
 * How much the pilots talk. The big moments always get a line whatever this is set to; it scales the chance of
 * speaking up about everything else, and how many such lines one phase may carry.
 */
public enum Chattiness {
    /** Only the big moments: kills, deaths, headshots, ejections, explosions and poses. */
    QUIET(0, 0),
    /** Half as likely to speak up about the smaller moments, and at most four lines a phase. */
    NORMAL(50, 4),
    /** The crew's original choice: every chance at full strength, and up to eight lines a phase. */
    CHATTY(100, 8);

    private final int scale;
    private final int maxLines;

    Chattiness(int scale, int maxLines) {
        this.scale = scale;
        this.maxLines = maxLines;
    }

    /**
     * @return the percentage chance a pilot speaks up about the event
     */
    public int chanceOf(ChatterEvent event) {
        return event.isForced() ? 100 : (event.chance() * scale) / 100;
    }

    /**
     * @return how many lines a phase may carry before only the big moments get through
     */
    public int maxLines() {
        return maxLines;
    }

    /**
     * @return the setting stored under {@code name}, or {@link #CHATTY} if there is none or it cannot be read
     */
    public static Chattiness parse(String name) {
        for (Chattiness chattiness : values()) {
            if (chattiness.name().equalsIgnoreCase(name)) {
                return chattiness;
            }
        }
        return CHATTY;
    }
}
