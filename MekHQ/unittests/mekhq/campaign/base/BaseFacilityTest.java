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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Node;

/** Tests the facility types' figures and {@link BaseFacility}'s construction and saving. */
class BaseFacilityTest {
    private static final LocalDate DAY = LocalDate.of(3135, 2, 1);

    @ParameterizedTest
    @EnumSource(BaseFacilityType.class)
    void levels_costMoreTakeLongerAndCostMoreToKeepAsTheyRise(BaseFacilityType type) {
        for (int level = 2; level <= BaseFacilityType.MAX_LEVEL; level++) {
            assertTrue(type.getBuildCost(level).isGreaterThan(type.getBuildCost(level - 1)), type + " cost");
            assertTrue(type.getBuildDays(level) > type.getBuildDays(level - 1), type + " days");
            assertTrue(type.getMonthlyUpkeep(level).isGreaterThan(type.getMonthlyUpkeep(level - 1)),
                  type + " upkeep");
        }
        assertTrue(type.getBuildCost(1).isPositive());
        assertTrue(type.getBuildDays(1) > 0);
    }

    @ParameterizedTest
    @EnumSource(BaseFacilityType.class)
    void level0AndOutOfRangeLevels_giveNothing(BaseFacilityType type) {
        for (int level : new int[] { 0, -1, BaseFacilityType.MAX_LEVEL + 1 }) {
            assertTrue(type.getBuildCost(level).isZero());
            assertEquals(0, type.getBuildDays(level));
            assertTrue(type.getMonthlyUpkeep(level).isZero());
            assertEquals(0, type.getCapacity(level));
            assertEquals(SITE_IMPROVISED, type.getRepairSiteFloor(level));
        }
    }

    @Test
    void garage_raisesTheRepairSiteWithEachLevel() {
        assertEquals(SITE_FACILITY_BASIC, BaseFacilityType.GARAGE.getRepairSiteFloor(1));
        assertEquals(SITE_FACILITY_MAINTENANCE, BaseFacilityType.GARAGE.getRepairSiteFloor(2));
        assertEquals(SITE_FACTORY_CONDITIONS, BaseFacilityType.GARAGE.getRepairSiteFloor(3));
    }

    @Test
    void onlyTheGarageSetsARepairSite() {
        for (int level = 1; level <= BaseFacilityType.MAX_LEVEL; level++) {
            assertEquals(SITE_IMPROVISED, BaseFacilityType.WAREHOUSE.getRepairSiteFloor(level));
            assertEquals(SITE_IMPROVISED, BaseFacilityType.BARRACKS.getRepairSiteFloor(level));
        }
    }

    @Test
    void warehouseAndBarracks_holdMoreWithEachLevel() {
        for (BaseFacilityType type : new BaseFacilityType[] { BaseFacilityType.WAREHOUSE,
                                                              BaseFacilityType.BARRACKS }) {
            for (int level = 2; level <= BaseFacilityType.MAX_LEVEL; level++) {
                assertTrue(type.getCapacity(level) > type.getCapacity(level - 1), type.toString());
            }
            assertTrue(type.getCapacity(1) > 0);
        }
        assertEquals(0, BaseFacilityType.GARAGE.getCapacity(BaseFacilityType.MAX_LEVEL));
    }

    @Test
    void newFacility_isUnbuiltAndNotWorthSaving() {
        BaseFacility facility = new BaseFacility(BaseFacilityType.GARAGE);

        assertEquals(0, facility.getLevel());
        assertFalse(facility.isUnderConstruction());
        assertEquals(0, facility.getTargetLevel());
        assertNull(facility.getCompletionDate());
        assertFalse(facility.isWorthSaving());
    }

    @Test
    void startConstruction_setsTheNextLevelAndItsCompletionDay() {
        BaseFacility facility = new BaseFacility(BaseFacilityType.GARAGE);

        facility.startConstruction(DAY);

        assertTrue(facility.isUnderConstruction());
        assertEquals(1, facility.getTargetLevel());
        assertEquals(DAY.plusDays(BaseFacilityType.GARAGE.getBuildDays(1)), facility.getCompletionDate());
        assertEquals(0, facility.getLevel(), "the level is not reached until construction is finished");
        assertTrue(facility.isWorthSaving());
    }

    @Test
    void completeIfDue_waitsForTheDay() {
        BaseFacility facility = new BaseFacility(BaseFacilityType.GARAGE);
        facility.startConstruction(DAY);
        LocalDate done = facility.getCompletionDate();

        assertFalse(facility.completeIfDue(done.minusDays(1)));
        assertEquals(0, facility.getLevel());
        assertTrue(facility.completeIfDue(done));
        assertEquals(1, facility.getLevel());
        assertFalse(facility.isUnderConstruction());
        assertNull(facility.getCompletionDate());
    }

    @Test
    void completeIfDue_finishesConstructionThatIsOverdue() {
        BaseFacility facility = new BaseFacility(BaseFacilityType.WAREHOUSE);
        facility.startConstruction(DAY);

        assertTrue(facility.completeIfDue(DAY.plusDays(1_000)));

        assertEquals(1, facility.getLevel());
    }

    @Test
    void completeIfDue_doesNothingWhenNothingIsBeingBuilt() {
        assertFalse(new BaseFacility(BaseFacilityType.BARRACKS).completeIfDue(DAY));
    }

    @Test
    void upgrade_keepsTheCurrentLevelWhileTheNextIsBuilt() {
        BaseFacility facility = new BaseFacility(BaseFacilityType.BARRACKS);
        facility.startConstruction(DAY);
        facility.completeIfDue(DAY.plusDays(1_000));

        facility.startConstruction(DAY.plusDays(1_000));

        assertEquals(1, facility.getLevel());
        assertEquals(2, facility.getTargetLevel());
        assertFalse(facility.isAtMaxLevel());
    }

    @Test
    void xmlRoundTrip_keepsALevelAndAnUpgradeInProgress() throws Exception {
        BaseFacility facility = new BaseFacility(BaseFacilityType.WAREHOUSE);
        facility.startConstruction(DAY);
        facility.completeIfDue(DAY.plusDays(1_000));
        facility.startConstruction(DAY.plusDays(1_000));

        BaseFacility loaded = BaseFacility.generateInstanceFromXML(write(facility));

        assertNotNull(loaded);
        assertEquals(BaseFacilityType.WAREHOUSE, loaded.getType());
        assertEquals(1, loaded.getLevel());
        assertEquals(2, loaded.getTargetLevel());
        assertEquals(facility.getCompletionDate(), loaded.getCompletionDate());
    }

    @Test
    void xmlRoundTrip_keepsABuiltFacilityWithNothingUnderConstruction() throws Exception {
        BaseFacility facility = new BaseFacility(BaseFacilityType.GARAGE);
        facility.startConstruction(DAY);
        facility.completeIfDue(DAY.plusDays(1_000));

        BaseFacility loaded = BaseFacility.generateInstanceFromXML(write(facility));

        assertNotNull(loaded);
        assertEquals(1, loaded.getLevel());
        assertFalse(loaded.isUnderConstruction());
    }

    @Test
    void loadingAnUnknownType_givesNothing() throws Exception {
        Node node = parse("<facility><type>MOAT</type><level>2</level></facility>");

        assertNull(BaseFacility.generateInstanceFromXML(node));
    }

    @Test
    void loadingOutOfRangeLevels_clampsThem() throws Exception {
        Node node = parse("<facility><type>GARAGE</type><level>9</level><targetLevel>4</targetLevel>"
                                + "<completionDate>3135-03-01</completionDate></facility>");

        BaseFacility loaded = BaseFacility.generateInstanceFromXML(node);

        assertNotNull(loaded);
        assertEquals(BaseFacilityType.MAX_LEVEL, loaded.getLevel());
        assertFalse(loaded.isUnderConstruction(), "a target above the maximum is not a real upgrade");
    }

    @Test
    void loadingAnUpgradeWithNoDate_dropsTheUpgrade() throws Exception {
        Node node = parse("<facility><type>BARRACKS</type><level>1</level><targetLevel>2</targetLevel></facility>");

        BaseFacility loaded = BaseFacility.generateInstanceFromXML(node);

        assertNotNull(loaded);
        assertEquals(1, loaded.getLevel());
        assertFalse(loaded.isUnderConstruction());
    }

    private static Node write(BaseFacility facility) throws Exception {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            facility.writeToXML(writer, 0);
        }
        return parse(text.toString());
    }

    private static Node parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance()
                     .newDocumentBuilder()
                     .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                     .getDocumentElement();
    }
}
