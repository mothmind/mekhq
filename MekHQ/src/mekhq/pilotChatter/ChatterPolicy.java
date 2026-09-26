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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;

import mekhq.pilotChatter.BattleEventDetector.Trigger;

/**
 * Chooses which pilots speak after a phase. Every big moment gets a line; the rest speak up by chance, as often as
 * the host's {@link Chattiness} says, capped so the chat never floods.
 */
public final class ChatterPolicy {
    private ChatterPolicy() {}

    /**
     * @param triggers   one per unit, as the detector found them
     * @param roll       returns a roll from 0 to {@code bound - 1}
     * @param chattiness how often the smaller moments get a line
     *
     * @return the triggers that get a line, the most important first: all the big moments, then the chance rolls
     *       that came up, up to the cap
     */
    public static List<Trigger> choose(List<Trigger> triggers, IntUnaryOperator roll, Chattiness chattiness) {
        List<Trigger> chosen = new ArrayList<>();
        for (Trigger trigger : triggers) {
            if (trigger.event().isForced() || (roll.applyAsInt(100) < chattiness.chanceOf(trigger.event()))) {
                chosen.add(trigger);
            }
        }
        chosen.sort(Comparator.comparing(Trigger::event));

        List<Trigger> capped = new ArrayList<>();
        for (Trigger trigger : chosen) {
            if (trigger.event().isForced() || (capped.size() < chattiness.maxLines())) {
                capped.add(trigger);
            }
        }
        return capped;
    }
}
