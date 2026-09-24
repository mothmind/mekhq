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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.OrbitalBay;
import megamek.common.OrbitalBay.WeaponClass;
import megamek.common.OrbitalSupport;
import megamek.common.enums.SkillLevel;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.SpaceStation;
import megamek.common.units.Warship;
import mekhq.campaign.Campaign;
import mekhq.campaign.enums.DragoonRating;
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
    void anUnflaggedShipDoesNotFire() {
        Unit warship = unitFor(warshipWith("Unassigned", bay("Nose", capital(40))));
        when(warship.isOrbitalSupport()).thenReturn(false);

        Campaign campaign = mock(Campaign.class);
        List<Unit> hangar = List.of(warship);
        when(campaign.getUnits()).thenReturn(hangar);

        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forCampaign(campaign));
    }

    @Test
    void onlyTheFlaggedShipsOfAHangarFire() {
        Unit onStation = unitFor(warshipWith("Invincible", bay("Nose", capital(40))));
        Unit inDock = unitFor(warshipWith("Vigilant", bay("Nose", capital(30))));
        when(inDock.isOrbitalSupport()).thenReturn(false);

        Campaign campaign = mock(Campaign.class);
        List<Unit> hangar = List.of(onStation, inDock);
        when(campaign.getUnits()).thenReturn(hangar);

        OrbitalSupport support = OrbitalSupportCalculator.forCampaign(campaign);

        assertEquals(1, support.strikesRemaining());
        assertEquals(List.of("Invincible"), support.shipNames());
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
    void aRatedEnemyFleetIsSizedByItsEquipment() {
        assertBayCountWithin(DragoonRating.DRAGOON_F, 1, 1);
        assertBayCountWithin(DragoonRating.DRAGOON_D, 1, 1);
        assertBayCountWithin(DragoonRating.DRAGOON_C, 1, 1);
        assertBayCountWithin(DragoonRating.DRAGOON_B, 2, 6);
        assertBayCountWithin(DragoonRating.DRAGOON_A, 4, 10);
        assertBayCountWithin(DragoonRating.DRAGOON_ASTAR, 8, 20);
    }

    @Test
    void aPoorlyEquippedEnemyFieldsLightGunsAndAWellEquippedOneFieldsWarshipArmament() {
        assertTrue(heaviestBayOver(DragoonRating.DRAGOON_F) <= 10,
              "an F-rated force fielded something heavier than a light naval gun");
        assertTrue(heaviestBayOver(DragoonRating.DRAGOON_ASTAR) >= 30,
              "an A*-rated force never fielded warship-grade armament");

        // Each rating's heaviest gun is at least as heavy as the one below it: the ladder never goes backwards.
        int previous = 0;
        for (DragoonRating rating : new DragoonRating[] { DragoonRating.DRAGOON_F, DragoonRating.DRAGOON_D,
                                                          DragoonRating.DRAGOON_C, DragoonRating.DRAGOON_B,
                                                          DragoonRating.DRAGOON_A, DragoonRating.DRAGOON_ASTAR }) {
            int heaviest = heaviestBayOver(rating);
            assertTrue(heaviest >= previous, rating + " fields lighter guns than the rating below it");
            previous = heaviest;
        }
    }

    @Test
    void everyRatingCanStillFieldAllThreeWeaponClasses() {
        // The single bay a low-rated force gets is meant to be an even chance between the three, so no tier may
        // quietly lose one.
        for (DragoonRating rating : DragoonRating.values()) {
            Set<WeaponClass> seen = new HashSet<>();
            for (int draw = 0; draw < 300; draw++) {
                OrbitalSupportCalculator.forOpposingForce("Fleet", rating)
                      .bays()
                      .forEach(bay -> seen.add(bay.weaponClass()));
            }
            assertEquals(Set.of(WeaponClass.ENERGY, WeaponClass.BALLISTIC, WeaponClass.CAPITAL_MISSILE), seen,
                  rating + " cannot field all three weapon classes");
        }
    }

    @Test
    void enemyGunneryFollowsTheForceSkillAndScatters() {
        int lowest = Integer.MAX_VALUE;
        int highest = 0;
        for (int draw = 0; draw < 500; draw++) {
            int gunnery = OrbitalSupportCalculator.opposingGunnery(SkillLevel.REGULAR);
            lowest = Math.min(lowest, gunnery);
            highest = Math.max(highest, gunnery);
        }

        // Regular is Gunnery 4 in MegaMek's own table, scattered two either way.
        assertEquals(2, lowest);
        assertEquals(6, highest);
    }

    @Test
    void enemyGunneryIsNeverBetterThanTwo() {
        for (int draw = 0; draw < 500; draw++) {
            assertTrue(OrbitalSupportCalculator.opposingGunnery(SkillLevel.LEGENDARY) >= 2,
              "a Legendary force scattered into a better gunner than the cap allows");
        }
    }

    @Test
    void anUnskilledEnemyCrewFallsBackToRegular() {
        assertEquals(OrbitalSupport.DEFAULT_GUNNERY, OrbitalSupportCalculator.opposingGunnery(null));
        assertEquals(OrbitalSupport.DEFAULT_GUNNERY, OrbitalSupportCalculator.opposingGunnery(SkillLevel.NONE));
    }

    private int heaviestBayOver(DragoonRating rating) {
        int heaviest = 0;
        for (int draw = 0; draw < 300; draw++) {
            for (OrbitalBay bay : OrbitalSupportCalculator.forOpposingForce("Fleet", rating).bays()) {
                heaviest = Math.max(heaviest, bay.attackValue());
            }
        }
        return heaviest;
    }

    @Test
    void aRatedEnemyFleetIsArmedWithRealCapitalWeapons() {
        // Every bay generated over many draws must be a gun a player could look up, at the Attack Value MegaMek
        // gives it. An invented number here would be invisible until someone checked the damage in play.
        Map<String, Integer> canon = Map.ofEntries(Map.entry("Naval Laser 35", 3),
              Map.entry("Naval Laser 45", 4),
              Map.entry("Naval Laser 55", 5),
              Map.entry("Naval PPC (Light)", 7),
              Map.entry("Naval PPC (Medium)", 9),
              Map.entry("Naval PPC (Heavy)", 15),
              Map.entry("Naval Autocannon (NAC/10)", 10),
              Map.entry("Naval Autocannon (NAC/20)", 20),
              Map.entry("Naval Autocannon (NAC/25)", 25),
              Map.entry("Naval Autocannon (NAC/30)", 30),
              Map.entry("Naval Autocannon (NAC/40)", 40),
              Map.entry("Naval Gauss (Light)", 15),
              Map.entry("Naval Gauss (Medium)", 25),
              Map.entry("Naval Gauss (Heavy)", 30),
              Map.entry("Capital Missile Launcher (Barracuda)", 2),
              Map.entry("Capital Missile Launcher (White Shark)", 3),
              Map.entry("Capital Missile Launcher (Killer Whale)", 4),
              Map.entry("Capital Missile Launcher (Kraken)", 10));

        for (DragoonRating rating : DragoonRating.values()) {
            for (int draw = 0; draw < 100; draw++) {
                for (OrbitalBay bay : OrbitalSupportCalculator.forOpposingForce("Fleet", rating).bays()) {
                    assertTrue(canon.containsKey(bay.name()), "not a canon capital weapon: " + bay.name());
                    assertEquals(canon.get(bay.name()), bay.attackValue(), bay.name());
                }
            }
        }
    }

    @Test
    void anUnratedEnemyGetsNoFleet() {
        assertEquals(OrbitalSupport.NONE, OrbitalSupportCalculator.forOpposingForce("Fleet", null));
    }

    private void assertBayCountWithin(DragoonRating rating, int fewest, int most) {
        int lowest = Integer.MAX_VALUE;
        int highest = 0;

        for (int draw = 0; draw < 500; draw++) {
            int bays = OrbitalSupportCalculator.forOpposingForce("Fleet", rating).bays().size();
            assertTrue((bays >= fewest) && (bays <= most),
                  "%s produced %d bays, outside %d-%d".formatted(rating, bays, fewest, most));
            lowest = Math.min(lowest, bays);
            highest = Math.max(highest, bays);
        }

        // Over 500 draws a range that never reaches its own ends is a generator bug, not luck.
        assertEquals(fewest, lowest, rating + " never produced its smallest fleet");
        assertEquals(most, highest, rating + " never produced its largest fleet");
    }

    @Test
    void theOpposingForcePackageIsUsableAndModest() {
        OrbitalSupport support = OrbitalSupportCalculator.forOpposingForce("Kurita Flotilla");

        assertTrue(support.isAvailable());
        assertEquals("Kurita Flotilla", support.bays().get(0).shipName());
        assertEquals(1, support.strikesRemaining());
        assertEquals(100, support.heaviestAvailableBay().orElseThrow().damage());
    }

    /** A campaign whose hangar holds these units, every one of them flagged for orbital support. */
    private Campaign campaignWith(Unit... units) {
        Campaign campaign = mock(Campaign.class);
        // Built before the stubbing starts: a mock created inside thenReturn() leaves the outer when() unfinished.
        List<Unit> hangar = List.of(units);
        when(campaign.getUnits()).thenReturn(hangar);
        return campaign;
    }

    private Unit unitFor(Entity entity) {
        Unit unit = mock(Unit.class);
        when(unit.getEntity()).thenReturn(entity);
        when(unit.isPresent()).thenReturn(true);
        when(unit.isFunctional()).thenReturn(true);
        when(unit.isMothballed()).thenReturn(false);
        when(unit.isSalvage()).thenReturn(false);
        when(unit.isOrbitalSupport()).thenReturn(true);
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
