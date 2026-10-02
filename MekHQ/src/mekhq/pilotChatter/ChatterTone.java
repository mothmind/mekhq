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

import java.util.Set;

import megamek.common.units.Entity;

/**
 * How a pilot is feeling when they speak, as one line for the prompt. A pilot whose unit is on fire screams; one who is
 * withdrawing is terrified, whatever they are saying; otherwise the worse their unit's condition, the more the strain
 * shows, as panic, distress or anger according to their personality. Moments with framing of their own - their unit destroyed, a headshot, ejecting, a
 * pose, a surrender - get no tone line.
 */
final class ChatterTone {
    /** At or below this share of armor and structure left, the unit is battered. */
    static final int BATTERED_PERCENT = 75;
    /** At or below this share left, or crippled, the unit is badly damaged. */
    static final int BADLY_DAMAGED_PERCENT = 50;
    /** At or below this share left, the unit is barely holding together. */
    static final int BREAKING_PERCENT = 25;

    static final String TERRIFIED = "They are terrified. Their unit is wrecked and they are trying to get out alive: let "
                                          + "the fear show plainly, whatever their usual manner.";
    static final String CORNERED = "They are terrified and cornered: they are shooting back out of fear, not courage. "
                                         + "Let it show.";
    static final String SCREAMING = "They are on fire and screaming: the cockpit is an oven around them. Let the line "
                                          + "come out as a scream of pain and terror.";

    private static final Set<ChatterEvent> OWN_FRAMING = Set.of(ChatterEvent.DESTROYED, ChatterEvent.HEADSHOT,
          ChatterEvent.EJECTED, ChatterEvent.POSE, ChatterEvent.SURRENDER);

    private ChatterTone() {}

    /**
     * @param entity      the speaker's unit
     * @param event       what they are reacting to
     * @param withdrawing whether the unit has pulled out of the fight
     * @param onFire      whether the unit is burning
     *
     * @return the line saying how they feel, or an empty string if nothing need be said
     */
    static String of(Entity entity, ChatterEvent event, boolean withdrawing, boolean onFire) {
        if (OWN_FRAMING.contains(event)) {
            return "";
        }
        if (onFire || (event == ChatterEvent.BURNING)) {
            return SCREAMING;
        }
        if (event == ChatterEvent.RETURNING_FIRE) {
            return CORNERED;
        }
        if (withdrawing || (event == ChatterEvent.WITHDRAWING)) {
            return TERRIFIED;
        }
        int left = percentLeft(entity);
        if (left < 0) {
            return "";
        }
        if (left <= BREAKING_PERCENT) {
            return "Their unit is barely holding together, with about " + left + "% of its armor and structure left. "
                         + "They are close to breaking: real panic, distress or fury, whichever fits their personality.";
        }
        if ((left <= BADLY_DAMAGED_PERCENT) || entity.isCrippled(true)) {
            return "Their unit is badly damaged, with about " + left + "% of its armor and structure left. They are "
                         + "rattled: show panic, distress or anger, whichever fits their personality.";
        }
        if (left <= BATTERED_PERCENT) {
            return "Their unit is battered, with about " + left + "% of its armor and structure left. The strain is "
                         + "starting to show.";
        }
        return "";
    }

    /**
     * @return the share of the unit's original armor and structure it still has, rounded to a whole percent, or
     *       {@code -1} if the unit started with none to measure
     */
    static int percentLeft(Entity entity) {
        int original = entity.getTotalOArmor() + entity.getTotalOInternal();
        if (original <= 0) {
            return -1;
        }
        int now = Math.max(0, entity.getTotalArmor()) + Math.max(0, entity.getTotalInternal());
        return (int) Math.round((100.0 * now) / original);
    }
}
