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
package mekhq.campaign;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A kill goes to the unit that landed the last hit; when nobody did, to whoever did the victim the most damage, ties
 * going to whichever of them hit it most recently.
 */
class KillCreditFallbackTest {

    private BipedMek victim;
    private BipedMek alpha;
    private BipedMek bravo;
    private Map<Integer, Entity> units;

    @BeforeEach
    void setUp() {
        victim = mek(1);
        alpha = mek(2);
        bravo = mek(3);
        units = Map.of(1, victim, 2, alpha, 3, bravo);
    }

    private static BipedMek mek(int id) {
        BipedMek mek = new BipedMek();
        mek.setId(id);
        return mek;
    }

    private Entity credited() {
        return ResolveScenarioTracker.creditedKiller(victim, units::get);
    }

    @Test
    void theLastHitStillWins() {
        victim.recordDamageFrom(alpha.getId(), 40);
        bravo.addKill(victim);

        assertSame(bravo, credited(), "the last hit beats the most damage");
    }

    @Test
    void withNoLastHitTheMostDamageWins() {
        victim.recordDamageFrom(alpha.getId(), 40);
        victim.recordDamageFrom(bravo.getId(), 15);

        assertSame(alpha, credited());
    }

    @Test
    void aTieGoesToWhoeverHitMostRecently() {
        victim.recordDamageFrom(bravo.getId(), 20);
        victim.recordDamageFrom(alpha.getId(), 20);

        assertSame(alpha, credited());
    }

    @Test
    void nobodyDamagedItSoNobodyIsCredited() {
        assertNull(credited());
    }

    @Test
    void anAttackerNoLongerKnownIsNotCredited() {
        victim.recordDamageFrom(99, 30);

        assertNull(credited());
    }
}
