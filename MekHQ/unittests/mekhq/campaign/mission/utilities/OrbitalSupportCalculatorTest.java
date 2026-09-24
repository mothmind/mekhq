/*
 * Copyright (C) 2025-2026 The MegaMek Team. All Rights Reserved.
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
package mekhq.campaign.mission.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.Vector;

import megamek.common.OrbitalBay;
import megamek.common.OrbitalBay.WeaponClass;
import megamek.common.OrbitalSupport;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.SpaceStation;
import megamek.common.units.Warship;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.Formation;
import mekhq.campaign.force.PlayerForce;
import mekhq.campaign.unit.Unit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OrbitalSupportCalculator}: which vessels can offer fire support, which weapons count towards a
 * bay's Attack Value, and that the resulting damage follows the book rather than a balance curve.
 */
class OrbitalSupportCalculatorTest {

    @Test
    void nullCampaignYieldsNoSupport() {
        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forCampaign(null));
    }

    @Test
    void nullEntityYieldsNoSupport() {
        assertFalse(OrbitalSupportCalculator.forEntity(null).isAvailable());
    }

    @Test
    void campaignWithNoShipsYieldsNoSupport() {
        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forCampaign(campaignWith(unitFor(mock(Mek.class)))));
    }

    @Test
    void aBaysAttackValueIsTheSumOfItsNavalWeapons() {
        // Three NAC/20s in one bay is Attack Value 60, which StratOps p.103 makes 600 standard damage.
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Invincible", bay("Nose NAC/20 Bay", capital(20), capital(20), capital(20))));

        assertEquals(1, support.strikesRemaining());
        OrbitalBay nose = support.heaviestAvailableBay().orElseThrow();
        assertEquals(60, nose.attackValue());
        assertEquals(600, nose.damage());
    }

    @Test
    void damageIsNotCapped() {
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Overwhelming", bay("Broadside", capital(500))));

        assertEquals(5000, support.heaviestAvailableBay().orElseThrow().damage());
    }

    @Test
    void radiusIsAlwaysFour() {
        assertEquals(4, OrbitalSupportCalculator.forEntity(
              warshipWith("Light", bay("Nose", capital(1)))).radius());
        assertEquals(4, OrbitalSupportCalculator.forEntity(
              warshipWith("Heavy", bay("Nose", capital(500)))).radius());
    }

    @Test
    void everyArmedBayIsOfferedSeparately() {
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Invincible", bay("Nose", capital(40)), bay("Aft", capital(10))));

        assertEquals(2, support.strikesRemaining());
        assertEquals("Nose", support.heaviestAvailableBay().orElseThrow().name());
        assertTrue(support.findBay("Aft").isPresent());
    }

    @Test
    void baysWithoutNavalWeaponsAreNotOffered() {
        assertFalse(OrbitalSupportCalculator.forEntity(
              warshipWith("PointDefenceOnly", bay("Nose", standard(10)))).isAvailable());
    }

    @Test
    void wreckedNavalWeaponsDoNotCountTowardsTheBay() {
        WeaponMounted wrecked = capital(50);
        when(wrecked.isOperable()).thenReturn(false);

        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Crippled", bay("Nose", wrecked, capital(10))));

        assertEquals(100, support.heaviestAvailableBay().orElseThrow().damage());
    }

    @Test
    void aBayWithEveryGunWreckedIsNotOffered() {
        WeaponMounted wrecked = capital(50);
        when(wrecked.isOperable()).thenReturn(false);

        assertFalse(OrbitalSupportCalculator.forEntity(warshipWith("Crippled", bay("Nose", wrecked))).isAvailable());
    }

    @Test
    void variableDamageWeaponsContributeNothing() {
        assertFalse(OrbitalSupportCalculator.forEntity(
              warshipWith("Variable", bay("Nose", capital(-1)))).isAvailable());
    }

    @Test
    void capitalWeaponsMountedOutsideBaysAreStillOffered() {
        // Build the weapon list before stubbing starts: creating a mock inside thenReturn() leaves Mockito with an
        // unfinished stubbing and throws.
        List<WeaponMounted> loose = List.of(capital(30));

        Warship warship = mock(Warship.class);
        when(warship.getShortName()).thenReturn("Unbayed");
        when(warship.getWeaponBayList()).thenReturn(List.of());
        when(warship.getTotalWeaponList()).thenReturn(loose);

        assertEquals(300, OrbitalSupportCalculator.forEntity(warship).heaviestAvailableBay().orElseThrow().damage());
    }

    @Test
    void aSpaceStationDoesNotAccompanyTheForce() {
        List<WeaponMounted> bays = List.of(bay("Nose", capital(100)));

        SpaceStation station = mock(SpaceStation.class);
        when(station.getShortName()).thenReturn("Olympus");
        when(station.getWeaponBayList()).thenReturn(bays);

        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forCampaign(campaignWith(unitFor(station))));
    }

    @Test
    void aMothballedShipCannotFire() {
        Unit unit = unitFor(warshipWith("Laid Up", bay("Nose", capital(100))));
        when(unit.isMothballed()).thenReturn(true);

        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forCampaign(campaignWith(unit)));
    }

    @Test
    void everyShipOnStationContributesItsBays() {
        Unit light = unitFor(warshipWith("Light", bay("Nose", capital(10))));
        Unit heavy = unitFor(warshipWith("Heavy", bay("Nose", capital(40)), bay("Aft", capital(30))));

        OrbitalSupport support = OrbitalSupportCalculator.forCampaign(campaignWith(light, heavy));

        assertEquals(3, support.strikesRemaining());
        assertEquals(List.of("Light", "Heavy"), support.shipNames());
    }

    @Test
    void aShipOutsideAnOrbitalSupportFormationDoesNotFire() {
        Unit warship = unitFor(warshipWith("Unassigned", bay("Nose", capital(40))));

        assertEquals(OrbitalSupport.NONE,
              OrbitalSupportCalculator.forCampaign(campaignWith(CombatRole.FRONTLINE, warship)));
    }

    @Test
    void aShipCountedTwiceThroughNestedFormationsStillFiresOnce() {
        Unit warship = unitFor(warshipWith("Invincible", bay("Nose", capital(40))));
        UUID id = UUID.randomUUID();

        Campaign campaign = mock(Campaign.class);
        when(campaign.getUnit(id)).thenReturn(warship);

        Vector<UUID> ids = new Vector<>(List.of(id));
        List<Formation> formations = List.of(orbitalFormation(CombatRole.ORBITAL_SUPPORT, ids),
              orbitalFormation(CombatRole.ORBITAL_SUPPORT, ids));

        PlayerForce playerForce = mock(PlayerForce.class);
        when(playerForce.getAllFormations()).thenReturn(formations);
        when(campaign.getPlayerForce()).thenReturn(playerForce);

        assertEquals(1, OrbitalSupportCalculator.forCampaign(campaign).strikesRemaining());
    }

    @Test
    void eachShipsBaysCarryItsOwnNameAndCrew() {
        Crew keen = mock(Crew.class);
        when(keen.getGunnery()).thenReturn(1);

        Warship elite = warshipWith("Elite", bay("Nose", capital(40)));
        when(elite.getCrew()).thenReturn(keen);

        OrbitalSupport support = OrbitalSupportCalculator.forCampaign(campaignWith(unitFor(elite),
              unitFor(warshipWith("Green", bay("Nose", capital(10))))));

        assertEquals("Elite", support.availableBays().get(0).shipName());
        assertEquals(1, support.availableBays().get(0).gunnery());
        assertEquals("Green", support.availableBays().get(1).shipName());
        assertEquals(OrbitalSupport.DEFAULT_GUNNERY, support.availableBays().get(1).gunnery());
    }

    @Test
    void navalLasersArriveTheSameTurn() {
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Laser Boat", bay("Nose", capitalOfClass(55, WeaponType.F_ENERGY))));

        assertEquals(WeaponClass.ENERGY, support.heaviestAvailableBay().orElseThrow().weaponClass());
    }

    @Test
    void navalAutocannonsArriveTheTurnAfter() {
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Gun Boat", bay("Nose", capitalOfClass(40, WeaponType.F_BALLISTIC))));

        assertEquals(WeaponClass.BALLISTIC, support.heaviestAvailableBay().orElseThrow().weaponClass());
    }

    @Test
    void capitalMissilesTakeADieRollToArrive() {
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Missile Boat", bay("Nose", capitalOfClass(30, WeaponType.F_MISSILE))));

        OrbitalBay nose = support.heaviestAvailableBay().orElseThrow();
        assertEquals(WeaponClass.CAPITAL_MISSILE, nose.weaponClass());
        assertTrue(nose.weaponClass().isVariableDelay());
    }

    @Test
    void aMixedBayArrivesAtThePaceOfItsSlowestGun() {
        // An energy weapon alongside a missile must not pull the salvo forward to the same turn.
        OrbitalSupport support = OrbitalSupportCalculator.forEntity(
              warshipWith("Mixed", bay("Nose",
                    capitalOfClass(20, WeaponType.F_ENERGY),
                    capitalOfClass(20, WeaponType.F_MISSILE))));

        assertEquals(WeaponClass.CAPITAL_MISSILE, support.heaviestAvailableBay().orElseThrow().weaponClass());
    }

    @Test
    void gunneryComesFromTheShipsOwnCrew() {
        Crew crew = mock(Crew.class);
        when(crew.getGunnery()).thenReturn(2);

        Warship warship = warshipWith("Elite", bay("Nose", capital(20)));
        when(warship.getCrew()).thenReturn(crew);

        assertEquals(2, OrbitalSupportCalculator.forEntity(warship).bays().get(0).gunnery());
    }

    @Test
    void aShipWithNoCrewRecordFallsBackToRegular() {
        Warship warship = warshipWith("Ghost", bay("Nose", capital(20)));
        when(warship.getCrew()).thenReturn(null);

        assertEquals(OrbitalSupport.DEFAULT_GUNNERY,
              OrbitalSupportCalculator.forEntity(warship).bays().get(0).gunnery());
    }

    @Test
    void theOpposingForcePackageIsUsableAndModest() {
        OrbitalSupport support = OrbitalSupportCalculator.forOpposingForce("Kurita Flotilla");

        assertTrue(support.isAvailable());
        assertEquals("Kurita Flotilla", support.bays().get(0).shipName());
        assertEquals(1, support.strikesRemaining());
        assertEquals(100, support.heaviestAvailableBay().orElseThrow().damage());
    }

    private Campaign campaignWith(Unit... units) {
        return campaignWith(CombatRole.ORBITAL_SUPPORT, units);
    }

    /** A campaign whose entire TO&E is one formation in the given role, holding these units. */
    private Campaign campaignWith(CombatRole role, Unit... units) {
        Campaign campaign = mock(Campaign.class);

        Vector<UUID> ids = new Vector<>();
        for (Unit unit : units) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            when(campaign.getUnit(id)).thenReturn(unit);
        }

        // Built before the stubbing starts: a mock created inside thenReturn() leaves the outer when() unfinished.
        List<Formation> formations = List.of(orbitalFormation(role, ids));

        PlayerForce playerForce = mock(PlayerForce.class);
        when(playerForce.getAllFormations()).thenReturn(formations);
        when(campaign.getPlayerForce()).thenReturn(playerForce);
        return campaign;
    }

    private Formation orbitalFormation(CombatRole role, Vector<UUID> unitIds) {
        Formation formation = mock(Formation.class);
        when(formation.getCombatRoleInMemory()).thenReturn(role);
        when(formation.getAllUnits(false)).thenReturn(unitIds);
        return formation;
    }

    private Unit unitFor(Entity entity) {
        Unit unit = mock(Unit.class);
        when(unit.getEntity()).thenReturn(entity);
        when(unit.isPresent()).thenReturn(true);
        when(unit.isFunctional()).thenReturn(true);
        when(unit.isMothballed()).thenReturn(false);
        when(unit.isSalvage()).thenReturn(false);
        return unit;
    }

    private Warship warshipWith(String name, WeaponMounted... bays) {
        // Build the bay list before stubbing starts: creating a mock inside thenReturn() leaves Mockito mid-stub.
        List<WeaponMounted> bayList = List.of(bays);

        Warship warship = mock(Warship.class);
        when(warship.getShortName()).thenReturn(name);
        when(warship.getWeaponBayList()).thenReturn(bayList);
        when(warship.getLocationAbbr(0)).thenReturn("");
        return warship;
    }

    private WeaponMounted bay(String name, WeaponMounted... weapons) {
        List<WeaponMounted> contents = List.of(weapons);

        WeaponMounted bay = mock(WeaponMounted.class);
        when(bay.getName()).thenReturn(name);
        when(bay.getLocation()).thenReturn(0);
        when(bay.getBayWeapons()).thenReturn(contents);
        return bay;
    }

    private WeaponMounted capital(int attackValue) {
        return weapon(attackValue, true);
    }

    private WeaponMounted standard(int damage) {
        return weapon(damage, false);
    }

    private WeaponMounted capitalOfClass(int attackValue, megamek.common.equipment.WeaponTypeFlag flag) {
        WeaponType type = mock(WeaponType.class);
        when(type.isCapital()).thenReturn(true);
        when(type.isSubCapital()).thenReturn(false);
        when(type.getDamage()).thenReturn(attackValue);
        when(type.hasFlag(flag)).thenReturn(true);

        WeaponMounted mounted = mock(WeaponMounted.class);
        when(mounted.getType()).thenReturn(type);
        when(mounted.isOperable()).thenReturn(true);
        return mounted;
    }

    private WeaponMounted weapon(int damage, boolean capital) {
        WeaponType type = mock(WeaponType.class);
        when(type.isCapital()).thenReturn(capital);
        when(type.isSubCapital()).thenReturn(false);
        when(type.getDamage()).thenReturn(damage);

        WeaponMounted mounted = mock(WeaponMounted.class);
        when(mounted.getType()).thenReturn(type);
        when(mounted.isOperable()).thenReturn(true);
        return mounted;
    }
}
