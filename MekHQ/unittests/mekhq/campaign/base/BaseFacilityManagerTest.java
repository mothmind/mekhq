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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static testUtilities.MHQTestUtilities.mockCampaign;

import java.time.LocalDate;
import java.util.Set;

import mekhq.campaign.Campaign;
import mekhq.campaign.CampaignLocationManager;
import mekhq.campaign.FixedLocation;
import mekhq.campaign.LocalHangar;
import mekhq.campaign.base.BaseFacilityManager.BuildResult;
import mekhq.campaign.enums.DailyReportType;
import mekhq.campaign.finances.Finances;
import mekhq.campaign.finances.Money;
import mekhq.campaign.finances.enums.TransactionType;
import mekhq.campaign.universe.PlanetarySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BaseFacilityManager}: moving money between the main force and a base, paying for and finishing
 * construction, charging upkeep, and what a garage gives the units at its base.
 */
class BaseFacilityManagerTest {
    private static final LocalDate DAY = LocalDate.of(3135, 2, 10);
    private static final LocalDate FIRST_OF_MONTH = LocalDate.of(3135, 3, 1);
    private static final Money MAIN_FORCE_FUNDS = Money.of(10_000_000);

    private Campaign campaign;
    private Finances finances;
    private PlayerBase base;

    @BeforeEach
    void setUp() {
        campaign = mockCampaign();
        finances = new Finances();
        finances.credit(TransactionType.STARTING_CAPITAL, DAY.minusDays(1), MAIN_FORCE_FUNDS, "Start");
        when(campaign.getPlayerForce().getFinances()).thenReturn(finances);
        when(campaign.getLocalDate()).thenReturn(DAY);

        base = new PlayerBase(new FixedLocation(mock(PlanetarySystem.class)));
        base.setDisplayName("Fort Test");
    }

    private void fundBase(Money amount) {
        base.getLedger().credit(DAY, amount, "Test funds");
    }

    @Nested
    class Transfers {
        @Test
        void deposit_movesMoneyFromTheMainForceToTheBase() {
            assertTrue(BaseFacilityManager.depositFromMainForce(campaign, base, Money.of(2_000_000)));

            assertEquals(Money.of(8_000_000), finances.getBalance());
            assertEquals(Money.of(2_000_000), base.getLedger().getBalance());
        }

        @Test
        void deposit_refusesMoreThanTheMainForceHas() {
            assertFalse(BaseFacilityManager.depositFromMainForce(campaign, base, MAIN_FORCE_FUNDS.plus(1)));

            assertEquals(MAIN_FORCE_FUNDS, finances.getBalance());
            assertTrue(base.getLedger().isBlank());
        }

        @Test
        void deposit_refusesAmountsThatAreNotPositive() {
            assertFalse(BaseFacilityManager.depositFromMainForce(campaign, base, Money.zero()));
            assertFalse(BaseFacilityManager.depositFromMainForce(campaign, base, Money.of(-100)));

            assertEquals(MAIN_FORCE_FUNDS, finances.getBalance());
            assertTrue(base.getLedger().isBlank());
        }

        @Test
        void withdraw_movesMoneyFromTheBaseToTheMainForce() {
            fundBase(Money.of(3_000_000));

            assertTrue(BaseFacilityManager.withdrawToMainForce(campaign, base, Money.of(1_000_000)));

            assertEquals(Money.of(2_000_000), base.getLedger().getBalance());
            assertEquals(Money.of(11_000_000), finances.getBalance());
        }

        @Test
        void withdraw_refusesMoreThanTheBaseHolds() {
            fundBase(Money.of(500));

            assertFalse(BaseFacilityManager.withdrawToMainForce(campaign, base, Money.of(501)));

            assertEquals(Money.of(500), base.getLedger().getBalance());
            assertEquals(MAIN_FORCE_FUNDS, finances.getBalance());
        }

        @Test
        void aTransferInAndBackLeavesTheMainForceWhereItStarted() {
            BaseFacilityManager.depositFromMainForce(campaign, base, Money.of(750_000));
            BaseFacilityManager.withdrawToMainForce(campaign, base, Money.of(750_000));

            assertEquals(MAIN_FORCE_FUNDS, finances.getBalance());
            assertTrue(base.getLedger().getBalance().isZero());
        }
    }

    @Nested
    class Construction {
        @Test
        void startConstruction_takesTheCostFromTheBaseLedgerAndStartsTheBuild() {
            fundBase(Money.of(1_000_000));

            BuildResult result = BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);

            assertEquals(BuildResult.STARTED, result);
            assertEquals(Money.of(1_000_000).minus(BaseFacilityType.GARAGE.getBuildCost(1)),
                  base.getLedger().getBalance());
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            assertTrue(garage.isUnderConstruction());
            assertEquals(DAY.plusDays(BaseFacilityType.GARAGE.getBuildDays(1)), garage.getCompletionDate());
        }

        @Test
        void startConstruction_doesNotTouchTheMainForceFunds() {
            fundBase(Money.of(1_000_000));

            BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);

            assertEquals(MAIN_FORCE_FUNDS, finances.getBalance());
        }

        @Test
        void startConstruction_refusesWhenTheBaseCannotPay() {
            fundBase(BaseFacilityType.GARAGE.getBuildCost(1).minus(1));

            BuildResult result = BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);

            assertEquals(BuildResult.INSUFFICIENT_FUNDS, result);
            assertFalse(base.getFacility(BaseFacilityType.GARAGE).isUnderConstruction());
            assertEquals(BaseFacilityType.GARAGE.getBuildCost(1).minus(1), base.getLedger().getBalance());
        }

        @Test
        void startConstruction_refusesASecondBuildOfTheSameFacility() {
            fundBase(Money.of(5_000_000));
            BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.WAREHOUSE);
            Money balanceAfterFirst = base.getLedger().getBalance();

            BuildResult result = BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.WAREHOUSE);

            assertEquals(BuildResult.ALREADY_UNDER_CONSTRUCTION, result);
            assertEquals(balanceAfterFirst, base.getLedger().getBalance());
        }

        @Test
        void startConstruction_buildsDifferentFacilitiesAtTheSameTime() {
            fundBase(Money.of(5_000_000));

            assertEquals(BuildResult.STARTED, BaseFacilityManager.startConstruction(DAY, base,
                  BaseFacilityType.WAREHOUSE));
            assertEquals(BuildResult.STARTED, BaseFacilityManager.startConstruction(DAY, base,
                  BaseFacilityType.BARRACKS));
        }

        @Test
        void startConstruction_refusesPastTheTopLevel() {
            fundBase(Money.of(100_000_000));
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            for (int level = 1; level <= BaseFacilityType.MAX_LEVEL; level++) {
                garage.startConstruction(DAY);
                garage.completeIfDue(DAY.plusDays(1_000));
            }
            Money before = base.getLedger().getBalance();

            BuildResult result = BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);

            assertEquals(BuildResult.MAX_LEVEL, result);
            assertEquals(before, base.getLedger().getBalance());
        }

        @Test
        void startConstruction_upgradePaysForTheNextLevel() {
            fundBase(Money.of(10_000_000));
            BaseFacility warehouse = base.getFacility(BaseFacilityType.WAREHOUSE);
            warehouse.startConstruction(DAY);
            warehouse.completeIfDue(DAY.plusDays(1_000));
            Money before = base.getLedger().getBalance();

            BuildResult result = BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.WAREHOUSE);

            assertEquals(BuildResult.STARTED, result);
            assertEquals(before.minus(BaseFacilityType.WAREHOUSE.getBuildCost(2)), base.getLedger().getBalance());
            assertEquals(2, warehouse.getTargetLevel());
        }

        @Test
        void monthlyUpkeep_countsBuiltLevelsOnly() {
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
            garage.startConstruction(DAY.plusDays(1_000));
            base.getFacility(BaseFacilityType.BARRACKS).startConstruction(DAY);

            assertEquals(BaseFacilityType.GARAGE.getMonthlyUpkeep(1), BaseFacilityManager.getMonthlyUpkeep(base));
        }

        @Test
        void monthlyUpkeep_isZeroForABaseWithNothingBuilt() {
            assertTrue(BaseFacilityManager.getMonthlyUpkeep(base).isZero());
        }
    }

    @Nested
    class NewDay {
        private CampaignLocationManager locationManager;

        @BeforeEach
        void setUpLocationManager() {
            locationManager = mock(CampaignLocationManager.class);
            when(locationManager.getPlayerBases()).thenReturn(Set.of(base));
            when(campaign.getCampaignLocationManager()).thenReturn(locationManager);
        }

        @Test
        void constructionThatIsDueIsFinishedAndReported() {
            fundBase(Money.of(1_000_000));
            BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);
            LocalDate done = base.getFacility(BaseFacilityType.GARAGE).getCompletionDate();
            when(campaign.getLocalDate()).thenReturn(done);

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(1, base.getFacilityLevel(BaseFacilityType.GARAGE));
            verify(campaign).addReport(eq(DailyReportType.GENERAL), anyString());
        }

        @Test
        void constructionThatIsNotDueYetKeepsBuilding() {
            fundBase(Money.of(1_000_000));
            BaseFacilityManager.startConstruction(DAY, base, BaseFacilityType.GARAGE);
            when(campaign.getLocalDate()).thenReturn(DAY.plusDays(1));

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(0, base.getFacilityLevel(BaseFacilityType.GARAGE));
            assertTrue(base.getFacility(BaseFacilityType.GARAGE).isUnderConstruction());
            verify(campaign, never()).addReport(eq(DailyReportType.GENERAL), anyString());
        }

        @Test
        void upkeepIsChargedOnTheFirstOfTheMonth() {
            fundBase(Money.of(1_000_000));
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
            Money before = base.getLedger().getBalance();
            when(campaign.getLocalDate()).thenReturn(FIRST_OF_MONTH);

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(before.minus(BaseFacilityType.GARAGE.getMonthlyUpkeep(1)), base.getLedger().getBalance());
            verify(campaign).addReport(eq(DailyReportType.FINANCES), anyString());
        }

        @Test
        void upkeepIsNotChargedOnOtherDays() {
            fundBase(Money.of(1_000_000));
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
            Money before = base.getLedger().getBalance();
            when(campaign.getLocalDate()).thenReturn(FIRST_OF_MONTH.plusDays(1));

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(before, base.getLedger().getBalance());
        }

        @Test
        void aBaseThatCannotPayUpkeepGoesIntoTheRedAndIsReportedAsOverdrawn() {
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
            when(campaign.getLocalDate()).thenReturn(FIRST_OF_MONTH);

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(BaseFacilityType.GARAGE.getMonthlyUpkeep(1).multipliedBy(-1),
                  base.getLedger().getBalance());
            verify(campaign, times(2)).addReport(eq(DailyReportType.FINANCES), anyString());
        }

        @Test
        void aBaseWithNothingBuiltPaysNothingAndReportsNothing() {
            when(campaign.getLocalDate()).thenReturn(FIRST_OF_MONTH);

            BaseFacilityManager.processNewDay(campaign);

            assertTrue(base.getLedger().isBlank());
            verify(campaign, never()).addReport(eq(DailyReportType.FINANCES), anyString());
        }

        @Test
        void aLevelFinishedOnTheFirstPaysUpkeepFromTheNextMonth() {
            fundBase(Money.of(1_000_000));
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
            // The warehouse is due to be finished on the very day upkeep is charged
            base.getFacility(BaseFacilityType.WAREHOUSE)
                  .startConstruction(FIRST_OF_MONTH.minusDays(BaseFacilityType.WAREHOUSE.getBuildDays(1)));
            Money before = base.getLedger().getBalance();
            when(campaign.getLocalDate()).thenReturn(FIRST_OF_MONTH);

            BaseFacilityManager.processNewDay(campaign);

            assertEquals(1, base.getFacilityLevel(BaseFacilityType.WAREHOUSE));
            assertEquals(before.minus(BaseFacilityType.GARAGE.getMonthlyUpkeep(1)), base.getLedger().getBalance());
        }
    }

    @Nested
    class GarageRepairSite {
        private void buildGarage(int levels) {
            BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
            for (int level = 1; level <= levels; level++) {
                garage.startConstruction(DAY);
                garage.completeIfDue(DAY.plusDays(1_000));
            }
        }

        @Test
        void aBaseWithNoGarage_givesNoFloor() {
            assertEquals(SITE_IMPROVISED, BaseFacilityManager.getRepairSiteFloor(base.getBaseHangar()));
        }

        @Test
        void unitsAtABaseWithAGarage_getTheGaragesSite() {
            buildGarage(1);
            assertEquals(SITE_FACILITY_BASIC, BaseFacilityManager.getRepairSiteFloor(base.getBaseHangar()));
            buildGarage(1);
            assertEquals(SITE_FACILITY_MAINTENANCE, BaseFacilityManager.getRepairSiteFloor(base.getBaseHangar()));
            buildGarage(1);
            assertEquals(SITE_FACTORY_CONDITIONS, BaseFacilityManager.getRepairSiteFloor(base.getBaseHangar()));
        }

        @Test
        void aGarageUnderConstruction_givesNothingYet() {
            buildGarage(1);
            base.getFacility(BaseFacilityType.GARAGE).startConstruction(DAY);

            assertEquals(SITE_FACILITY_BASIC, BaseFacilityManager.getRepairSiteFloor(base.getBaseHangar()));
        }

        @Test
        void unitsOutsideAnyBase_getNoFloor() {
            buildGarage(3);

            assertEquals(SITE_IMPROVISED, BaseFacilityManager.getRepairSiteFloor(new LocalHangar()));
        }

        @Test
        void nothing_givesNoFloor() {
            assertEquals(SITE_IMPROVISED, BaseFacilityManager.getRepairSiteFloor(null));
        }
    }
}
