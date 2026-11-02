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
package mekhq.campaign.base;

import static mekhq.campaign.unit.Unit.SITE_FACILITY_BASIC;
import static mekhq.campaign.unit.Unit.SITE_FACILITY_MAINTENANCE;
import static mekhq.campaign.unit.Unit.SITE_FACTORY_CONDITIONS;
import static mekhq.campaign.unit.Unit.SITE_IMPROVISED;
import static mekhq.utilities.MHQInternationalization.getTextAt;

import mekhq.campaign.finances.Money;

/**
 * The facilities a {@link PlayerBase} can build and upgrade. Each has {@link #MAX_LEVEL} levels, and each level has its
 * own build cost, build time and monthly upkeep. The cost of a level is paid from the base's own {@link BaseLedger}
 * when construction starts; the upkeep of the levels already built is charged to it on the first of each month.
 *
 * <p>The figures below are starting values, kept in one place so they are easy to tune.</p>
 */
public enum BaseFacilityType {
    /**
     * Sets the repair site of every unit kept at the base to at least the level's site: a basic facility, then a
     * maintenance facility, then factory conditions.
     */
    GARAGE("garage",
          new long[] { 500_000, 2_000_000, 8_000_000 },
          new int[] { 30, 60, 120 },
          new long[] { 5_000, 20_000, 80_000 },
          new int[] { 0, 0, 0 }),
    /** Spare-parts storage, in tons. */
    WAREHOUSE("warehouse",
          new long[] { 250_000, 1_000_000, 4_000_000 },
          new int[] { 30, 60, 120 },
          new long[] { 2_500, 10_000, 40_000 },
          new int[] { 100, 500, 2_000 }),
    /** Housing for the people stationed at the base. */
    BARRACKS("barracks",
          new long[] { 300_000, 1_200_000, 5_000_000 },
          new int[] { 30, 60, 120 },
          new long[] { 3_000, 12_000, 50_000 },
          new int[] { 50, 200, 600 });

    /** The highest level any facility can reach. */
    public static final int MAX_LEVEL = 3;

    private static final String RESOURCE_BUNDLE = "mekhq.resources.Bases";
    private static final int[] GARAGE_SITES = { SITE_FACILITY_BASIC, SITE_FACILITY_MAINTENANCE,
                                                SITE_FACTORY_CONDITIONS };

    private final String resourceKey;
    private final long[] buildCosts;
    private final int[] buildDays;
    private final long[] monthlyUpkeeps;
    private final int[] capacities;

    BaseFacilityType(String resourceKey, long[] buildCosts, int[] buildDays, long[] monthlyUpkeeps, int[] capacities) {
        this.resourceKey = resourceKey;
        this.buildCosts = buildCosts;
        this.buildDays = buildDays;
        this.monthlyUpkeeps = monthlyUpkeeps;
        this.capacities = capacities;
    }

    /** @return the name shown to the player */
    public String getDisplayName() {
        return getTextAt(RESOURCE_BUNDLE, "facility." + resourceKey + ".name");
    }

    /** @return what the facility does, in a sentence */
    public String getDescription() {
        return getTextAt(RESOURCE_BUNDLE, "facility." + resourceKey + ".description");
    }

    /** @return the cost of building {@code level}, going from the level below it; zero outside 1 to {@link #MAX_LEVEL} */
    public Money getBuildCost(int level) {
        return isBuildable(level) ? Money.of(buildCosts[level - 1]) : Money.zero();
    }

    /** @return the days it takes to build {@code level}; zero outside 1 to {@link #MAX_LEVEL} */
    public int getBuildDays(int level) {
        return isBuildable(level) ? buildDays[level - 1] : 0;
    }

    /** @return the monthly upkeep of a facility that has reached {@code level}; zero for level 0 */
    public Money getMonthlyUpkeep(int level) {
        return isBuildable(level) ? Money.of(monthlyUpkeeps[level - 1]) : Money.zero();
    }

    /**
     * @return the capacity at {@code level}: tons of spare parts for a {@link #WAREHOUSE}, people for
     *       {@link #BARRACKS}, and zero for anything else or for level 0
     */
    public int getCapacity(int level) {
        return isBuildable(level) ? capacities[level - 1] : 0;
    }

    /**
     * @return the lowest repair site ({@code Unit.SITE_*}) the facility gives the units at its base at {@code level}.
     *       Anything that is not a {@link #GARAGE}, and level 0, gives {@code SITE_IMPROVISED}, which is below every
     *       site and so changes nothing.
     */
    public int getRepairSiteFloor(int level) {
        return ((this == GARAGE) && isBuildable(level)) ? GARAGE_SITES[level - 1] : SITE_IMPROVISED;
    }

    /** @return the unit the capacity is counted in, as text, such as {@code tons} */
    public String getCapacityUnit() {
        return getTextAt(RESOURCE_BUNDLE, "facility." + resourceKey + ".capacityUnit");
    }

    private static boolean isBuildable(int level) {
        return (level >= 1) && (level <= MAX_LEVEL);
    }
}
