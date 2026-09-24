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
package mekhq.campaign.campaignOptions;

import static mekhq.utilities.MHQInternationalization.getTextAt;

/**
 * How a scenario decides who has a ship overhead willing to fire.
 *
 * <p>Under {@link #FLAT_CHANCE} the force always fields whatever its flagged ships can provide, and only the
 * opposing force rolls, against a fixed percentage. Under {@link #ORBITAL_CONTEST} both sides roll, each against a
 * chance that moves over the course of the contract. Under {@link #STRATEGY_ROLL} the question is asked of the
 * people instead: each vessel's commander rolls their Strategy to make the firing window, and the enemy rolls
 * against their force's own skill. In both of the latter, the two sides are independent, so a battle can be fought
 * under both fleets, one, or neither.</p>
 *
 * @author mothmind
 */
public enum OrbitalSupportMethod {
    /** The opposing force rolls against the configured enemy chance; the player always has their own. */
    FLAT_CHANCE("FLAT_CHANCE"),
    /** Both sides roll, each against its own chance, which moves over the course of the contract. */
    ORBITAL_CONTEST("ORBITAL_CONTEST"),
    /** Both sides roll 2D6 against a skill: the vessel commander's Strategy, or the enemy force's own skill. */
    STRATEGY_ROLL("STRATEGY_ROLL");

    private static final String RESOURCE_BUNDLE = "mekhq.resources.OrbitalSupportMethod";

    private final String lookupName;
    private final String label;
    private final String tooltip;

    OrbitalSupportMethod(String lookupName) {
        this.lookupName = lookupName;
        this.label = generateLabel();
        this.tooltip = generateTooltip();
    }

    public String getLookupName() {
        return lookupName;
    }

    public String getLabel() {
        return label;
    }

    public String getTooltip() {
        return tooltip;
    }

    /** @return True when each side rolls its own chance of being on station rather than the player being certain. */
    public boolean isContested() {
        return this == ORBITAL_CONTEST;
    }

    /** @return True when a firing position is earned on a 2D6 skill roll rather than a percentage. */
    public boolean isStrategyRoll() {
        return this == STRATEGY_ROLL;
    }

    /**
     * @return True when the opposing force's fleet is sized from its equipment rating rather than being credited
     *       with the same token bay every time. Only the flat chance method keeps the token bay.
     */
    public boolean usesRatedEnemyFleet() {
        return this != FLAT_CHANCE;
    }

    private String generateLabel() {
        return getTextAt(RESOURCE_BUNDLE, "OrbitalSupportMethod." + lookupName + ".label");
    }

    private String generateTooltip() {
        return getTextAt(RESOURCE_BUNDLE, "OrbitalSupportMethod." + lookupName + ".tooltip");
    }

    public static OrbitalSupportMethod fromLookupName(String lookupName) {
        for (OrbitalSupportMethod method : OrbitalSupportMethod.values()) {
            if (method.lookupName.equals(lookupName)) {
                return method;
            }
        }
        // The same landing spot as an absent tag: the behaviour campaigns had before the choice existed.
        return FLAT_CHANCE;
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
