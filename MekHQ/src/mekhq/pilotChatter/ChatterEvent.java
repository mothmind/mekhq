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
 * Something that happened to a pilot that they might speak up about, from the most to the least important. The big
 * moments always get a line; the rest get one by chance.
 */
public enum ChatterEvent {
    HEADSHOT(100, "was killed instantly by a shot to the head."),
    POSE(100, "struck a dramatic pose instead of moving. Deliver a one-liner worthy of it."),
    DESTROYED(100, "had their unit destroyed around them."),
    KILL(100, "destroyed an enemy unit."),
    EJECTED(100, "ejected from their stricken unit."),
    INTERNAL_EXPLOSION(100, "felt something explode inside their own unit."),
    PILOT_HURT(70, "was hurt in the cockpit."),
    DAMAGED(45, "took hits."),
    IN_ACTION(25, "is in the thick of it this turn.");

    private final int chance;
    private final String description;

    ChatterEvent(int chance, String description) {
        this.chance = chance;
        this.description = description;
    }

    /**
     * @return the percentage chance the pilot speaks up about it
     */
    public int chance() {
        return chance;
    }

    /**
     * @return whether the pilot always speaks up about it
     */
    public boolean isForced() {
        return chance >= 100;
    }

    /**
     * @return what happened, to follow "The pilot ..."
     */
    public String description() {
        return description;
    }
}
