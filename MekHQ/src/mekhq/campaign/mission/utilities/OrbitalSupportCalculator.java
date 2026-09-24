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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import megamek.common.OrbitalBay;
import megamek.common.OrbitalBay.WeaponClass;
import megamek.common.OrbitalSupport;
import megamek.common.annotations.Nullable;
import megamek.common.compute.Compute;
import megamek.common.enums.SkillLevel;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Entity;
import megamek.common.units.Jumpship;
import megamek.common.units.SpaceStation;
import mekhq.campaign.Campaign;
import mekhq.campaign.enums.DragoonRating;
import mekhq.campaign.unit.Unit;

/**
 * Works out the orbital fire support a force brings to a surface battle, from the naval armament of a JumpShip or
 * WarShip that stays in orbit rather than joining the fight.
 *
 * <p>The numbers come from the rules rather than from balance guesswork. Strategic Operations, Orbit-to-Surface Fire
 * (p. 103), resolves such an attack per weapon bay — "the attack from each bay is targeted and resolved separately" —
 * and sets "the base Damage Value of each attack" to "the Attack Value of each bay", converted to standard scale at
 * ten standard points per capital point. The blast radius is a flat four hexes for every bay, since the book degrades
 * the multiplier by two per hex out to four and no further, rather than scaling the radius with the gun.</p>
 *
 * <p>So a ship contributes its list of bays, each able to fire once, and the player chooses which to spend. A heavy
 * WarShip is genuinely devastating, exactly as the rules intend; the limit on it is the number of bays and the choice
 * of when to use them, not an artificial damage ceiling.</p>
 *
 * @author mothmind
 */
public class OrbitalSupportCalculator {

    private OrbitalSupportCalculator() {
    }

    /**
     * Finds the orbital support the campaign's own ships can offer.
     *
     * <p>Which vessels fire is the commander's decision, not a calculation: a ship flagged for orbital support in
     * the TO&amp;E stays in orbit and puts its naval bays at the disposal of every scenario, and an unflagged ship
     * contributes nothing however well armed. Every flagged ship is used, not merely the heaviest, so a flotilla
     * brings its whole broadside and the player chooses bay by bay which of it to spend.</p>
     *
     * <p>Only a JumpShip or WarShip that is present, crewed and not laid up counts; a mothballed hull or one marked
     * for salvage is in no state to fire, whatever its flag says. Space stations are excluded: they do not
     * accompany a force to a contract.</p>
     *
     * @param campaign The campaign whose hangar is read
     *
     * @return The pooled support available, or {@link OrbitalSupport#NONE} when no suitable ship is on station
     */
    public static OrbitalSupport forCampaign(Campaign campaign) {
        return forCampaign(campaign, unit -> true);
    }

    /**
     * As {@link #forCampaign(Campaign)}, but with a say over which of the flagged ships actually made it into a
     * firing position.
     *
     * <p>Under the methods that ask each vessel to earn its window, a flagged ship that fails is simply not there
     * this scenario. The test is applied per ship rather than to the force, so a flotilla can arrive in part.</p>
     *
     * @param campaign  The campaign whose hangar is read
     * @param onStation Decides whether a given flagged, fit vessel is in position this scenario
     *
     * @return The pooled support available, or {@link OrbitalSupport#NONE} when nothing is on station
     */
    public static OrbitalSupport forCampaign(Campaign campaign, Predicate<Unit> onStation) {
        List<OrbitalBay> bays = new ArrayList<>();

        for (Unit unit : supportingUnits(campaign)) {
            if (onStation.test(unit)) {
                bays.addAll(forEntity(unit.getEntity()).bays());
            }
        }

        return bays.isEmpty() ? OrbitalSupport.NONE : new OrbitalSupport(bays);
    }

    /**
     * Lists the vessels that would provide orbital support if asked, before anything rolls for a firing position.
     *
     * <p>This is what the briefing reads: a commander needs to see which of their ships are on the roster and what
     * each brings, whether or not this particular scenario's dice go their way.</p>
     *
     * @param campaign The campaign whose hangar is read
     *
     * @return Every flagged vessel in a fit state to fire, in hangar order
     */
    public static List<Unit> supportingUnits(@Nullable Campaign campaign) {
        List<Unit> supporting = new ArrayList<>();
        if (campaign == null) {
            return supporting;
        }

        for (Unit unit : campaign.getUnits()) {
            if ((unit != null) && unit.isOrbitalSupport() && isAvailableSupportShip(unit)) {
                supporting.add(unit);
            }
        }

        return supporting;
    }

    /**
     * @return True when this unit is a JumpShip or WarShip in a fit state to provide fire support
     */
    private static boolean isAvailableSupportShip(Unit unit) {
        if ((unit == null) || (unit.getEntity() == null)) {
            return false;
        }

        Entity entity = unit.getEntity();
        if (!(entity instanceof Jumpship) || (entity instanceof SpaceStation)) {
            return false;
        }

        return unit.isPresent() && unit.isFunctional() && !unit.isMothballed() && !unit.isSalvage();
    }

    /**
     * Reads a vessel's naval bays into the fire support it can offer.
     *
     * <p>A bay's Attack Value is the sum of the capital and sub-capital weapons inside it that are still in working
     * order, so a bay whose guns have been shot away contributes nothing even while the bay itself survives. Bays
     * holding no naval weapons — standard-scale point defence, for instance — are left out entirely rather than
     * offered as an empty choice.</p>
     *
     * @param entity The vessel in orbit
     *
     * @return The bays it can fire, or {@link OrbitalSupport#NONE} when it carries no working naval armament
     */
    public static OrbitalSupport forEntity(Entity entity) {
        if (entity == null) {
            return OrbitalSupport.NONE;
        }

        List<OrbitalBay> bays = new ArrayList<>();

        for (WeaponMounted bay : entity.getWeaponBayList()) {
            int attackValue = 0;
            WeaponClass weaponClass = null;
            for (WeaponMounted weapon : bay.getBayWeapons()) {
                int value = navalAttackValue(weapon);
                if (value > 0) {
                    attackValue += value;
                    weaponClass = slower(weaponClass, classOf(weapon));
                }
            }
            if (attackValue > 0) {
                bays.add(new OrbitalBay(bayName(entity, bay), attackValue, weaponClass));
            }
        }

        // Some designs mount capital weapons directly rather than in bays. Treat each as a bay of its own so they
        // are still offered, rather than silently dropping the ship's entire armament.
        if (bays.isEmpty()) {
            for (WeaponMounted weapon : entity.getTotalWeaponList()) {
                int attackValue = navalAttackValue(weapon);
                if (attackValue > 0) {
                    bays.add(new OrbitalBay(bayName(entity, weapon), attackValue, classOf(weapon)));
                }
            }
        }

        return bays.isEmpty()
              ? OrbitalSupport.NONE
              : new OrbitalSupport(entity.getShortName(), bays, gunneryOf(entity));
    }

    /**
     * Sorts a weapon into the class that decides how long its fire takes to reach the surface: energy the same turn,
     * ballistic the next, capital missiles 1D6 turns later (StratOps p.103).
     *
     * @return The weapon's class, defaulting to ballistic for anything that declares none
     */
    private static WeaponClass classOf(WeaponMounted mounted) {
        if (!(mounted.getType() instanceof WeaponType weaponType)) {
            return WeaponClass.BALLISTIC;
        }
        if (weaponType.hasFlag(WeaponType.F_MISSILE)) {
            return WeaponClass.CAPITAL_MISSILE;
        }
        if (weaponType.hasFlag(WeaponType.F_ENERGY)) {
            return WeaponClass.ENERGY;
        }
        return WeaponClass.BALLISTIC;
    }

    /**
     * Picks the slower of two classes, so a mixed bay arrives at the pace of its slowest gun rather than letting a
     * single energy weapon pull a missile salvo forward.
     *
     * @return The slower class, or whichever is non-null when only one is
     */
    private static WeaponClass slower(WeaponClass current, WeaponClass candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        // Declaration order is fastest to slowest: ENERGY, BALLISTIC, CAPITAL_MISSILE.
        return (candidate.ordinal() > current.ordinal()) ? candidate : current;
    }

    /**
     * Reads the firing crew's Gunnery skill, which sets the base to-hit number for every strike the ship delivers.
     *
     * <p>This is the vessel's own crew rather than the ground commander's: the guns are theirs and the shot is
     * theirs. A ship with no crew record falls back to Regular rather than refusing to fire.</p>
     *
     * @return The vessel's Gunnery skill
     */
    private static int gunneryOf(Entity entity) {
        return (entity.getCrew() == null) ? OrbitalSupport.DEFAULT_GUNNERY : entity.getCrew().getGunnery();
    }

    /**
     * @return The capital-scale Attack Value this mount contributes, or zero when it is not a working naval weapon.
     *       Variable-damage weapons report a negative value and contribute nothing.
     */
    private static int navalAttackValue(WeaponMounted mounted) {
        if ((mounted == null) || !mounted.isOperable()) {
            return 0;
        }
        if (!(mounted.getType() instanceof WeaponType weaponType)) {
            return 0;
        }
        if (!weaponType.isCapital() && !weaponType.isSubCapital()) {
            return 0;
        }
        return Math.max(0, weaponType.getDamage());
    }

    /**
     * @return A name a player can type at the {@code /orbitalstrike} prompt, qualified by location so two bays of the
     *       same guns in different arcs can be told apart
     */
    private static String bayName(Entity entity, WeaponMounted bay) {
        String location = entity.getLocationAbbr(bay.getLocation());
        String name = bay.getName();
        return (location == null || location.isBlank()) ? name : location + " " + name;
    }

    /**
     * Builds the support an opposing force receives when it turns out to have a ship of its own overhead.
     *
     * <p>The enemy's vessel is never generated as a unit, so there is no armament to read. It is given a single
     * modest naval bay instead: dangerous enough to force the player to spread out, but well short of what a heavy
     * WarShip in the player's own hangar would bring.</p>
     *
     * @param shipName The name to credit the bombardment to in reports
     *
     * @return The opposing force's support package
     */
    public static OrbitalSupport forOpposingForce(String shipName) {
        return new OrbitalSupport(shipName,
              List.of(new OrbitalBay("Naval Bay", OPFOR_BAY_ATTACK_VALUE)),
              OPFOR_GUNNERY);
    }

    /**
     * Arms an opposing force's unseen vessel from its equipment rating.
     *
     * <p>Where {@link #forOpposingForce(String)} credits every enemy with the same token bay, this reads what the
     * force could actually afford. The number of bays comes from the rating; each one is a real capital weapon
     * drawn at random, so the player faces guns they can look up rather than a round number, and two contracts
     * against the same rating do not produce the same ship.</p>
     *
     * @param shipName The name to credit the bombardment to in reports
     * @param rating   The opposing force's equipment rating
     *
     * @return The opposing force's support package, or {@link OrbitalSupport#NONE} for a rating with no fleet
     */
    public static OrbitalSupport forOpposingForce(String shipName, @Nullable DragoonRating rating) {
        return forOpposingForce(shipName, rating, null);
    }

    /**
     * As {@link #forOpposingForce(String, DragoonRating)}, with the vessel's crew read from the force's skill.
     *
     * @param shipName   The name to credit the bombardment to in reports
     * @param rating     The opposing force's equipment rating
     * @param forceSkill The opposing force's overall skill, or null to assume a Regular crew
     *
     * @return The opposing force's support package, or {@link OrbitalSupport#NONE} for a rating with no fleet
     */
    public static OrbitalSupport forOpposingForce(String shipName, @Nullable DragoonRating rating,
          @Nullable SkillLevel forceSkill) {
        int[] range = (rating == null) ? null : OPFOR_BAY_COUNTS.get(rating);
        if (range == null) {
            return OrbitalSupport.NONE;
        }

        int bayCount = range[0] + Compute.randomInt((range[1] - range[0]) + 1);
        List<OrbitalBay> armoury = OPFOR_WEAPONS.get(rating);
        List<OrbitalBay> bays = new ArrayList<>();
        for (int index = 0; index < bayCount; index++) {
            bays.add(armoury.get(Compute.randomInt(armoury.size())));
        }

        return new OrbitalSupport(shipName, bays, opposingGunnery(forceSkill));
    }

    /**
     * Works out the Gunnery of an unseen enemy vessel's crew.
     *
     * <p>The force's own skill sets the standard, since the ship came from the same organisation as everything
     * else the player is fighting. A naval gun crew is not the same people as the ground force, though, so the
     * result is scattered two points either way - an otherwise green outfit can still have one good crew, and an
     * elite one can have a gunner having a bad day. The scatter never produces better than Gunnery 2: whatever the
     * force is worth, this is a crew nobody has met firing at a target they cannot see.</p>
     *
     * @param forceSkill The opposing force's overall skill, or null to assume a Regular crew
     *
     * @return The Gunnery skill for the vessel's bays
     */
    public static int opposingGunnery(@Nullable SkillLevel forceSkill) {
        if ((forceSkill == null) || forceSkill.isNone()) {
            return OPFOR_GUNNERY;
        }

        int base = forceSkill.getDefaultSkillValues()[0];
        int scattered = base + (Compute.randomInt(OPFOR_GUNNERY_SCATTER * 2 + 1) - OPFOR_GUNNERY_SCATTER);
        return Math.max(OPFOR_BEST_GUNNERY, scattered);
    }

    /**
     * @param rating The opposing force's equipment rating
     *
     * @return The fewest and most bays a vessel of that rating can carry, or null when the rating fields no fleet
     */
    public static @Nullable int[] bayCountRange(@Nullable DragoonRating rating) {
        int[] range = (rating == null) ? null : OPFOR_BAY_COUNTS.get(rating);
        return (range == null) ? null : range.clone();
    }

    /**
     * The Attack Value of the single bay an opposing force is credited with, in capital scale. Comparable to one
     * NAC/10, which lands 100 points at the target hex.
     */
    private static final int OPFOR_BAY_ATTACK_VALUE = 10;

    // The capital weapons MegaMek defines, at the Attack Values it gives them. Real guns rather than invented
    // numbers: a force that turns out to have a WarShip overhead should be carrying something a player can look up.
    private static final OrbitalBay NL_35 = new OrbitalBay("Naval Laser 35", 3, WeaponClass.ENERGY);
    private static final OrbitalBay NL_45 = new OrbitalBay("Naval Laser 45", 4, WeaponClass.ENERGY);
    private static final OrbitalBay NL_55 = new OrbitalBay("Naval Laser 55", 5, WeaponClass.ENERGY);
    private static final OrbitalBay NPPC_LIGHT = new OrbitalBay("Naval PPC (Light)", 7, WeaponClass.ENERGY);
    private static final OrbitalBay NPPC_MEDIUM = new OrbitalBay("Naval PPC (Medium)", 9, WeaponClass.ENERGY);
    private static final OrbitalBay NPPC_HEAVY = new OrbitalBay("Naval PPC (Heavy)", 15, WeaponClass.ENERGY);

    private static final OrbitalBay NAC_10 = new OrbitalBay("Naval Autocannon (NAC/10)", 10, WeaponClass.BALLISTIC);
    private static final OrbitalBay NAC_20 = new OrbitalBay("Naval Autocannon (NAC/20)", 20, WeaponClass.BALLISTIC);
    private static final OrbitalBay NAC_25 = new OrbitalBay("Naval Autocannon (NAC/25)", 25, WeaponClass.BALLISTIC);
    private static final OrbitalBay NAC_30 = new OrbitalBay("Naval Autocannon (NAC/30)", 30, WeaponClass.BALLISTIC);
    private static final OrbitalBay NAC_40 = new OrbitalBay("Naval Autocannon (NAC/40)", 40, WeaponClass.BALLISTIC);
    private static final OrbitalBay NGAUSS_LIGHT = new OrbitalBay("Naval Gauss (Light)", 15, WeaponClass.BALLISTIC);
    private static final OrbitalBay NGAUSS_MEDIUM = new OrbitalBay("Naval Gauss (Medium)", 25, WeaponClass.BALLISTIC);
    private static final OrbitalBay NGAUSS_HEAVY = new OrbitalBay("Naval Gauss (Heavy)", 30, WeaponClass.BALLISTIC);

    private static final OrbitalBay BARRACUDA =
          new OrbitalBay("Capital Missile Launcher (Barracuda)", 2, WeaponClass.CAPITAL_MISSILE);
    private static final OrbitalBay WHITE_SHARK =
          new OrbitalBay("Capital Missile Launcher (White Shark)", 3, WeaponClass.CAPITAL_MISSILE);
    private static final OrbitalBay KILLER_WHALE =
          new OrbitalBay("Capital Missile Launcher (Killer Whale)", 4, WeaponClass.CAPITAL_MISSILE);
    private static final OrbitalBay KRAKEN =
          new OrbitalBay("Capital Missile Launcher (Kraken)", 10, WeaponClass.CAPITAL_MISSILE);

    /**
     * What an unseen enemy vessel can be armed with, by the force's equipment rating.
     *
     * <p>The rating is what the force can afford, and capital guns are the most expensive thing it could be
     * spending on, so it sets the calibre as well as the number of bays. An F-rated outfit that somehow has a hull
     * overhead is firing the lightest naval guns made; an A*-rated fleet fields what a real WarShip carries. Each
     * tier keeps all three weapon classes available, so the single bay a low-rated force gets is still an even
     * chance between energy, ballistic and missile.</p>
     */
    private static final Map<DragoonRating, List<OrbitalBay>> OPFOR_WEAPONS = Map.of(
          DragoonRating.DRAGOON_F, List.of(NL_35, NAC_10, BARRACUDA),
          DragoonRating.DRAGOON_D, List.of(NL_35, NL_45, NAC_10, BARRACUDA, WHITE_SHARK),
          DragoonRating.DRAGOON_C, List.of(NL_45, NL_55, NAC_10, NAC_20, WHITE_SHARK, KILLER_WHALE),
          DragoonRating.DRAGOON_B,
          List.of(NL_55, NPPC_LIGHT, NAC_20, NGAUSS_LIGHT, KILLER_WHALE, KRAKEN),
          DragoonRating.DRAGOON_A,
          List.of(NPPC_LIGHT, NPPC_MEDIUM, NAC_25, NAC_30, NGAUSS_MEDIUM, KRAKEN),
          DragoonRating.DRAGOON_ASTAR,
          List.of(NPPC_MEDIUM, NPPC_HEAVY, NAC_30, NAC_40, NGAUSS_HEAVY, KRAKEN));

    /**
     * How many bays an unseen enemy vessel carries, by the force's equipment rating: the fewest it can have and the
     * most, inclusive.
     *
     * <p>A force that cannot afford working BattleMechs does not have a broadside. Below B rating the ship is
     * whatever hull could be scraped up, good for one shot; from B upwards it is a real warship, and an A*-rated
     * fleet fields something that can flatten a valley at leisure.</p>
     */
    private static final Map<DragoonRating, int[]> OPFOR_BAY_COUNTS = Map.of(
          DragoonRating.DRAGOON_F, new int[] { 1, 1 },
          DragoonRating.DRAGOON_D, new int[] { 1, 1 },
          DragoonRating.DRAGOON_C, new int[] { 1, 1 },
          DragoonRating.DRAGOON_B, new int[] { 2, 6 },
          DragoonRating.DRAGOON_A, new int[] { 4, 10 },
          DragoonRating.DRAGOON_ASTAR, new int[] { 8, 20 });

    /**
     * The Gunnery skill credited to an opposing force's unseen vessel. Regular: the enemy gets a competent crew
     * rather than an unusually good or bad one, since there is no unit to read a real skill from.
     */
    private static final int OPFOR_GUNNERY = OrbitalSupport.DEFAULT_GUNNERY;

    /** How far either way an enemy naval crew's Gunnery is scattered from what the force's skill would suggest. */
    private static final int OPFOR_GUNNERY_SCATTER = 2;

    /** The best Gunnery an unseen enemy crew can scatter into. Lower is better, so this is a floor. */
    private static final int OPFOR_BEST_GUNNERY = 2;
}
