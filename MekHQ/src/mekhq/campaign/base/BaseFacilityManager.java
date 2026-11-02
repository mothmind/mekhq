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

import static mekhq.campaign.unit.Unit.SITE_IMPROVISED;
import static mekhq.utilities.MHQInternationalization.getFormattedTextAt;

import java.time.LocalDate;

import jakarta.annotation.Nullable;
import mekhq.campaign.Campaign;
import mekhq.campaign.enums.DailyReportType;
import mekhq.campaign.finances.Money;
import mekhq.campaign.finances.enums.TransactionType;
import mekhq.campaign.location.ILocation;
import mekhq.campaign.location.LocationUtils;
import mekhq.campaign.parts.Part;

/**
 * What the campaign does with the money and facilities of its {@link PlayerBase}s: moving funds between the main force
 * and a base, starting construction, finishing it and charging upkeep on the new day, and reading what a base's
 * facilities give.
 */
public final class BaseFacilityManager {
    private static final String RESOURCE_BUNDLE = "mekhq.resources.Bases";

    /** What came of a request to build or upgrade a facility. */
    public enum BuildResult {
        /** Construction has started and its cost has been taken from the base's ledger. */
        STARTED,
        /** The facility is already being built or upgraded. */
        ALREADY_UNDER_CONSTRUCTION,
        /** The facility is at its highest level. */
        MAX_LEVEL,
        /** The base's ledger does not hold the cost. */
        INSUFFICIENT_FUNDS
    }

    private BaseFacilityManager() {}

    // region Funds

    /**
     * Moves money from the campaign's funds to a base's ledger.
     *
     * @return {@code false}, changing nothing, if the amount is not positive or the campaign cannot afford it
     */
    public static boolean depositFromMainForce(Campaign campaign, PlayerBase base, Money amount) {
        LocalDate today = campaign.getLocalDate();
        if (!amount.isPositive() || !campaign.getPlayerForce().getFinances()
                                          .debit(TransactionType.MISCELLANEOUS, today, amount,
                                                getFormattedTextAt(RESOURCE_BUNDLE, "ledger.main.toBase",
                                                      base.getDisplayName()))) {
            return false;
        }
        base.getLedger().credit(today, amount, getFormattedTextAt(RESOURCE_BUNDLE, "ledger.fromMainForce"));
        return true;
    }

    /**
     * Moves money from a base's ledger to the campaign's funds.
     *
     * @return {@code false}, changing nothing, if the amount is not positive or the base's ledger does not hold it
     */
    public static boolean withdrawToMainForce(Campaign campaign, PlayerBase base, Money amount) {
        LocalDate today = campaign.getLocalDate();
        if (!base.getLedger().debit(today, amount, getFormattedTextAt(RESOURCE_BUNDLE, "ledger.toMainForce"))) {
            return false;
        }
        campaign.getPlayerForce().getFinances()
              .credit(TransactionType.MISCELLANEOUS, today, amount,
                    getFormattedTextAt(RESOURCE_BUNDLE, "ledger.main.fromBase", base.getDisplayName()));
        return true;
    }

    // endregion Funds

    // region Construction

    /**
     * Starts building the next level of a facility at a base, taking the cost from the base's ledger.
     *
     * @param today the day construction starts
     * @param base  the base
     * @param type  the facility to build, or to upgrade if the base already has it
     *
     * @return what came of the request; nothing is changed unless it is {@link BuildResult#STARTED}
     */
    public static BuildResult startConstruction(LocalDate today, PlayerBase base, BaseFacilityType type) {
        BaseFacility facility = base.getFacility(type);
        if (facility.isUnderConstruction()) {
            return BuildResult.ALREADY_UNDER_CONSTRUCTION;
        }
        if (facility.isAtMaxLevel()) {
            return BuildResult.MAX_LEVEL;
        }
        int nextLevel = facility.getLevel() + 1;
        String description = getFormattedTextAt(RESOURCE_BUNDLE, "ledger.construction", type.getDisplayName(),
              nextLevel);
        if (!base.getLedger().debit(today, type.getBuildCost(nextLevel), description)) {
            return BuildResult.INSUFFICIENT_FUNDS;
        }
        facility.startConstruction(today);
        return BuildResult.STARTED;
    }

    /** @return the monthly upkeep of everything the base has built; a level under construction is not yet counted */
    public static Money getMonthlyUpkeep(PlayerBase base) {
        Money total = Money.zero();
        for (BaseFacility facility : base.getFacilities()) {
            total = total.plus(facility.getType().getMonthlyUpkeep(facility.getLevel()));
        }
        return total;
    }

    // endregion Construction

    // region New day

    /**
     * Runs a new day for every player base: charges the upkeep on the first of the month, then finishes any
     * construction whose day has come.
     */
    public static void processNewDay(Campaign campaign) {
        LocalDate today = campaign.getLocalDate();
        for (PlayerBase base : campaign.getCampaignLocationManager().getPlayerBases()) {
            if (today.getDayOfMonth() == 1) {
                chargeUpkeep(campaign, base, today);
            }
            for (BaseFacility facility : base.getFacilities()) {
                if (facility.completeIfDue(today)) {
                    campaign.addReport(DailyReportType.GENERAL,
                          getFormattedTextAt(RESOURCE_BUNDLE, "report.completed",
                                facility.getType().getDisplayName(), base.getDisplayName(), facility.getLevel()));
                }
            }
        }
    }

    private static void chargeUpkeep(Campaign campaign, PlayerBase base, LocalDate today) {
        Money upkeep = getMonthlyUpkeep(base);
        if (!upkeep.isPositive()) {
            return;
        }
        base.getLedger().charge(today, upkeep, getFormattedTextAt(RESOURCE_BUNDLE, "ledger.upkeep"));
        campaign.addReport(DailyReportType.FINANCES,
              getFormattedTextAt(RESOURCE_BUNDLE, "report.upkeep", base.getDisplayName(),
                    upkeep.toAmountAndSymbolString()));
        if (!base.getLedger().getBalance().isPositiveOrZero()) {
            campaign.addReport(DailyReportType.FINANCES,
                  getFormattedTextAt(RESOURCE_BUNDLE, "report.overdrawn", base.getDisplayName(),
                        base.getLedger().getBalance().toAmountAndSymbolString()));
        }
    }

    // endregion New day

    // region What the facilities give

    /**
     * @param item a unit, or anything else with a place in the location tree
     *
     * @return the lowest repair site ({@code Unit.SITE_*}) its base's garage gives it; {@code SITE_IMPROVISED}, below
     *       every site and so no change, if it is not at a base, is on its way somewhere, or the base has no garage
     */
    public static int getRepairSiteFloor(@Nullable ILocation item) {
        if ((item == null) || LocationUtils.isInTransit(item)) {
            return SITE_IMPROVISED;
        }
        if (LocationUtils.findEffectiveBase(item) instanceof PlayerBase base) {
            return BaseFacilityType.GARAGE.getRepairSiteFloor(base.getFacilityLevel(BaseFacilityType.GARAGE));
        }
        return SITE_IMPROVISED;
    }

    /** @return the spare parts the base's warehouse holds, in tons, as the parts report their own tonnage */
    public static double getWarehouseTonsUsed(PlayerBase base) {
        double tons = 0.0;
        for (Part part : base.getBaseWarehouse().getParts()) {
            if (part.isSpare() && part.isPresent()) {
                tons += Math.max(0.0, part.getTonnage()) * part.getTotalQuantity();
            }
        }
        return tons;
    }

    /** @return the people at the base, not counting anyone still travelling there */
    public static int getPersonnelCount(PlayerBase base) {
        return base.getBasePersonnel().size();
    }

    /** @return how much of its type the base's facility can hold: tons for a warehouse, people for barracks */
    public static int getCapacity(PlayerBase base, BaseFacilityType type) {
        return type.getCapacity(base.getFacilityLevel(type));
    }

    // endregion What the facilities give
}
