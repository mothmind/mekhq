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
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MekHQ was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package mekhq.gui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import mekhq.campaign.finances.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests how the Bases tab reads the C-bills typed into a transfer. */
class BaseDetailPanelTest {

    @Test
    void parseAmount_readsAWholeNumber() {
        assertEquals(Money.of(250_000), BaseDetailPanel.parseAmount("250000"));
    }

    @Test
    void parseAmount_ignoresSeparatorsAndSurroundingSpace() {
        assertEquals(Money.of(1_500_000), BaseDetailPanel.parseAmount("  1,500,000 "));
        assertEquals(Money.of(1_500_000), BaseDetailPanel.parseAmount("1 500 000"));
        assertEquals(Money.of(1_500_000), BaseDetailPanel.parseAmount("1_500_000"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "0", "000", "-5", "12.5", "1.5e6", "abc", "10 C-bills", "1234567890123456" })
    void parseAmount_refusesAnythingElse(String text) {
        assertNull(BaseDetailPanel.parseAmount(text));
    }
}
