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

import mekhq.MHQOptions;

/**
 * How the pilots talk, as the host has set it in MekHQ's options. Where they get their lines from is
 * {@link ChatterSettings}.
 *
 * @param chattiness   how often they speak up about the smaller moments
 * @param enemyChatter whether enemy pilots speak at all
 * @param loreChance   the percentage of the company's own lines asked to draw on its story; 0 for never
 */
public record ChatterTuning(Chattiness chattiness, boolean enemyChatter, int loreChance) {
    public static final int DEFAULT_LORE_CHANCE = 15;
    public static final ChatterTuning DEFAULT = new ChatterTuning(Chattiness.CHATTY, true, DEFAULT_LORE_CHANCE);

    public ChatterTuning {
        loreChance = Math.clamp(loreChance, 0, 100);
    }

    public static ChatterTuning fromOptions(MHQOptions options) {
        return new ChatterTuning(options.getPilotChatterChattiness(), options.getPilotChatterEnemyChatter(),
              options.getPilotChatterLoreChance());
    }
}
