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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import megamek.common.units.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a pilot sounds: terrified while withdrawing, and more strained the less of their unit is left.
 */
class ChatterToneTest {

    /** A unit that started with 100 points of armor and structure, with {@code left} of them remaining. */
    private static Entity unit(int left, boolean crippled) {
        Entity entity = mock(Entity.class);
        when(entity.getTotalOArmor()).thenReturn(60);
        when(entity.getTotalOInternal()).thenReturn(40);
        when(entity.getTotalArmor()).thenReturn(Math.max(0, left - 40));
        when(entity.getTotalInternal()).thenReturn(Math.min(40, left));
        when(entity.isCrippled(true)).thenReturn(crippled);
        return entity;
    }

    @Test
    @DisplayName("a withdrawing pilot is terrified in everything they say, not just the announcement")
    void withdrawingIsTerrified() {
        assertEquals(ChatterTone.TERRIFIED, ChatterTone.of(unit(100, false), ChatterEvent.WITHDRAWING, false, false));
        assertEquals(ChatterTone.TERRIFIED, ChatterTone.of(unit(100, false), ChatterEvent.DAMAGED, true, false));
        assertEquals(ChatterTone.TERRIFIED, ChatterTone.of(unit(100, false), ChatterEvent.IN_ACTION, true, false));
    }

    @Test
    @DisplayName("returning fire while withdrawing is fear, not courage")
    void returningFireIsCornered() {
        assertEquals(ChatterTone.CORNERED, ChatterTone.of(unit(30, true), ChatterEvent.RETURNING_FIRE, true, false));
    }

    @Test
    @DisplayName("a healthy or lightly scratched unit gets no tone line")
    void healthyHasNoTone() {
        assertEquals("", ChatterTone.of(unit(100, false), ChatterEvent.DAMAGED, false, false));
        assertEquals("", ChatterTone.of(unit(76, false), ChatterEvent.DAMAGED, false, false));
    }

    @Test
    @DisplayName("three quarters left is battered")
    void battered() {
        String tone = ChatterTone.of(unit(75, false), ChatterEvent.DAMAGED, false, false);
        assertTrue(tone.contains("battered") && tone.contains("75%"), tone);
    }

    @Test
    @DisplayName("half left, or crippled, is rattled, shown according to personality")
    void rattled() {
        String half = ChatterTone.of(unit(50, false), ChatterEvent.DAMAGED, false, false);
        assertTrue(half.contains("rattled") && half.contains("whichever fits their personality"), half);
        String crippled = ChatterTone.of(unit(70, true), ChatterEvent.KILL, false, false);
        assertTrue(crippled.contains("rattled"), "a crippled unit is badly damaged however much is left: " + crippled);
    }

    @Test
    @DisplayName("a quarter or less left is close to breaking")
    void breaking() {
        String tone = ChatterTone.of(unit(20, true), ChatterEvent.PILOT_HURT, false, false);
        assertTrue(tone.contains("close to breaking") && tone.contains("20%"), tone);
    }

    @Test
    @DisplayName("moments with their own framing get no tone line, however bad things are")
    void ownFramingHasNoTone() {
        for (ChatterEvent event : new ChatterEvent[] { ChatterEvent.DESTROYED, ChatterEvent.HEADSHOT,
              ChatterEvent.EJECTED, ChatterEvent.POSE, ChatterEvent.SURRENDER }) {
            assertEquals("", ChatterTone.of(unit(10, true), event, true, false), event.name());
        }
    }

    @Test
    @DisplayName("a unit with nothing to measure gets no condition line")
    void nothingToMeasure() {
        Entity entity = mock(Entity.class);
        assertEquals(-1, ChatterTone.percentLeft(entity));
        assertEquals("", ChatterTone.of(entity, ChatterEvent.DAMAGED, false, false));
    }

    @Test
    @DisplayName("a pilot on fire screams, whatever else is going on")
    void onFireScreams() {
        assertEquals(ChatterTone.SCREAMING, ChatterTone.of(unit(100, false), ChatterEvent.BURNING, false, true));
        assertEquals(ChatterTone.SCREAMING, ChatterTone.of(unit(40, true), ChatterEvent.DAMAGED, true, true));
        assertEquals(ChatterTone.SCREAMING, ChatterTone.of(unit(40, true), ChatterEvent.RETURNING_FIRE, true, true));
    }

    @Test
    @DisplayName("a burning pilot's own big moments keep their own framing")
    void onFireKeepsOwnFraming() {
        assertEquals("", ChatterTone.of(unit(10, true), ChatterEvent.DESTROYED, false, true));
    }
}
