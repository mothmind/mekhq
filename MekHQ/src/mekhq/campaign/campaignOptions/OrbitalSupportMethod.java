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
 * <p>The two methods differ in whether orbital space is contested. Under {@link #FLAT_CHANCE} it is not: the force
 * simply fields whatever its own hangar can provide, and the opposing force rolls separately against a fixed
 * percentage, so both sides can bombard the same battle. Under {@link #ORBITAL_CONTEST} the space above the battle
 * holds one fleet or the other, and a single roll settles which.</p>
 *
 * @author mothmind
 */
public enum OrbitalSupportMethod {
    /** The opposing force rolls independently against the configured enemy chance; the player always has their own. */
    FLAT_CHANCE("FLAT_CHANCE"),
    /** One roll per scenario decides which side holds orbit, or that neither does. */
    ORBITAL_CONTEST("ORBITAL_CONTEST");

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

    /** @return True when orbital space is contested, so at most one side bombards. */
    public boolean isContested() {
        return this == ORBITAL_CONTEST;
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
