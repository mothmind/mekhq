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
package mekhq.campaign.digitalGM.stratCon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ambush chance and ambush round of forces that rush into engagements.
 */
class StratConAmbushPlannerTest {

    @Test
    @DisplayName("an unscouted hex is 25% on its own")
    void unscoutedHex() {
        assertEquals(25, StratConAmbushPlanner.ambushChance(false, 0));
    }

    @Test
    @DisplayName("each unscouted neighbour adds 5%")
    void unscoutedNeighbours() {
        assertEquals(0, StratConAmbushPlanner.ambushChance(true, 0), "a fully scouted area is safe");
        assertEquals(5, StratConAmbushPlanner.ambushChance(true, 1));
        assertEquals(30, StratConAmbushPlanner.ambushChance(true, 6));
    }

    @Test
    @DisplayName("walking blind into the middle of unscouted ground is the worst case, at 55%")
    void worstCase() {
        assertEquals(55, StratConAmbushPlanner.ambushChance(false, 6));
        assertEquals(35, StratConAmbushPlanner.ambushChance(false, 2));
    }

    @Test
    @DisplayName("the ambush springs between round 4 and round 12, when the chance roll comes in under the chance")
    void ambushRound() {
        assertEquals(0, StratConAmbushPlanner.ambushRound(40, 40, 0), "a roll at the chance misses");
        assertEquals(0, StratConAmbushPlanner.ambushRound(0, 0, 0), "no chance, no ambush");
        assertEquals(4, StratConAmbushPlanner.ambushRound(40, 39, 0));
        assertEquals(12, StratConAmbushPlanner.ambushRound(40, 0, 8));
    }

    @Test
    @DisplayName("neighbours are counted on the map only, and scouted ones by what has been revealed")
    void neighbourCounting() {
        StratConTrackState track = new StratConTrackState();
        track.setWidth(5);
        track.setHeight(5);
        StratConCoords middle = new StratConCoords(2, 2);
        StratConCoords corner = new StratConCoords(0, 0);

        assertEquals(6, StratConAmbushPlanner.neighbours(track, middle));
        assertEquals(0, StratConAmbushPlanner.scoutedNeighbours(track, middle));
        assertTrue(StratConAmbushPlanner.neighbours(track, corner) < 6, "a corner hex has fewer neighbours on the map");

        track.getRevealedCoords().add(middle.translate(0));
        track.getRevealedCoords().add(middle.translate(3));
        track.getRevealedCoords().add(new StratConCoords(4, 4));

        assertEquals(2, StratConAmbushPlanner.scoutedNeighbours(track, middle));
    }
}
