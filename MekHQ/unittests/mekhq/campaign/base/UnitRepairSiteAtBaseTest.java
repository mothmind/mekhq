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
import static mekhq.campaign.unit.Unit.SITE_FIELD_WORKSHOP;
import static mekhq.campaign.unit.Unit.SITE_IMPROVISED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;

import mekhq.campaign.Campaign;
import mekhq.campaign.FixedLocation;
import mekhq.campaign.unit.Unit;
import mekhq.campaign.universe.PlanetarySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testUtilities.parts.PartsScenario;
import testUtilities.parts.UnitFixture;

/** Tests that a base's garage sets the repair site of the real units kept at that base, and only theirs. */
class UnitRepairSiteAtBaseTest {
    private static final LocalDate DAY = LocalDate.of(3135, 2, 10);

    private Campaign campaign;
    private PlayerBase base;
    private Unit unitAtBase;
    private Unit unitInMainForce;

    @BeforeEach
    void setUp() {
        PartsScenario scenario = PartsScenario.create();
        campaign = scenario.getCampaign();
        base = new PlayerBase(new FixedLocation(mock(PlanetarySystem.class)));
        campaign.getCampaignLocationManager().addPlayerBase(base);

        unitAtBase = scenario.withUnit(UnitFixture.LOCUST_LCT_1V);
        campaign.getPlayerForce().getHangar().removeUnit(unitAtBase.getId());
        base.getBaseHangar().addUnit(unitAtBase);
        unitInMainForce = scenario.withUnit(UnitFixture.LEOPARD_DROPSHIP);
    }

    private void buildGarage(int levels) {
        BaseFacility garage = base.getFacility(BaseFacilityType.GARAGE);
        for (int level = 1; level <= levels; level++) {
            garage.startConstruction(DAY);
            garage.completeIfDue(DAY.plusDays(1_000));
        }
    }

    @Test
    void withoutAGarage_theChosenSiteStands() {
        unitAtBase.setSite(SITE_FIELD_WORKSHOP);

        assertEquals(SITE_FIELD_WORKSHOP, unitAtBase.getSite());
    }

    @Test
    void aGarage_liftsALowerChosenSiteToItsOwn() {
        unitAtBase.setSite(SITE_IMPROVISED);
        buildGarage(2);

        assertEquals(SITE_FACILITY_MAINTENANCE, unitAtBase.getSite());
        assertEquals(Unit.getSiteMod(SITE_FACILITY_MAINTENANCE).getValue(), unitAtBase.getSiteMod().getValue());
        assertEquals(Unit.getSiteName(SITE_FACILITY_MAINTENANCE), unitAtBase.getCurrentSiteName());
    }

    @Test
    void aGarage_neverLowersASiteThePlayerChoseHigher() {
        unitAtBase.setSite(SITE_FACTORY_CONDITIONS);
        buildGarage(1);

        assertEquals(SITE_FACTORY_CONDITIONS, unitAtBase.getSite());
    }

    @Test
    void aGarageUnderUpgrade_stillGivesTheLevelItHasBuilt() {
        buildGarage(1);

        base.getFacility(BaseFacilityType.GARAGE).startConstruction(DAY);

        assertEquals(SITE_FACILITY_BASIC, unitAtBase.getSite());
    }

    @Test
    void aGarage_doesNotReachUnitsInTheMainForce() {
        unitInMainForce.setSite(SITE_FIELD_WORKSHOP);
        buildGarage(3);

        assertEquals(SITE_FIELD_WORKSHOP, unitInMainForce.getSite());
    }
}
