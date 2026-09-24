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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import megamek.common.OrbitalBay;
import megamek.common.OrbitalBay.WeaponClass;
import megamek.common.OrbitalSupport;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Entity;
import megamek.common.units.Jumpship;
import megamek.common.units.SpaceStation;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.Formation;
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
     * <p>Which vessels fire is the commander's decision, not a calculation: a formation given the
     * {@link CombatRole#ORBITAL_SUPPORT} role stays in orbit and puts its naval bays at the disposal of every
     * scenario, and nothing outside such a formation contributes. Every qualifying ship in those formations is used,
     * not merely the heaviest, so a flotilla brings its whole broadside and the player chooses bay by bay which of
     * it to spend.</p>
     *
     * <p>Only a JumpShip or WarShip that is present, crewed and not laid up counts; a mothballed hull or one marked
     * for salvage is in no state to fire. Space stations are excluded: they do not accompany a force to a contract.
     * Anything else in the formation - a DropShip, a fighter wing parked alongside - simply contributes nothing
     * rather than being an error.</p>
     *
     * @param campaign The campaign whose orbital support formations are read
     *
     * @return The pooled support available, or {@link OrbitalSupport#NONE} when no suitable ship is on station
     */
    public static OrbitalSupport forCampaign(Campaign campaign) {
        if (campaign == null) {
            return OrbitalSupport.NONE;
        }

        List<OrbitalBay> bays = new ArrayList<>();
        // A formation nested inside another that is also on orbital support would otherwise be read twice, and its
        // ships would fire the same bays twice over.
        Set<UUID> counted = new LinkedHashSet<>();

        for (Formation formation : campaign.getPlayerForce().getAllFormations()) {
            CombatRole role = formation.getCombatRoleInMemory();
            if ((role == null) || !role.isOrbitalSupport()) {
                continue;
            }

            for (UUID unitId : formation.getAllUnits(false)) {
                if (!counted.add(unitId)) {
                    continue;
                }

                Unit unit = campaign.getUnit(unitId);
                if (isAvailableSupportShip(unit)) {
                    bays.addAll(forEntity(unit.getEntity()).bays());
                }
            }
        }

        return bays.isEmpty() ? OrbitalSupport.NONE : new OrbitalSupport(bays);
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
     * The Attack Value of the single bay an opposing force is credited with, in capital scale. Comparable to one
     * NAC/10, which lands 100 points at the target hex.
     */
    private static final int OPFOR_BAY_ATTACK_VALUE = 10;

    /**
     * The Gunnery skill credited to an opposing force's unseen vessel. Regular: the enemy gets a competent crew
     * rather than an unusually good or bad one, since there is no unit to read a real skill from.
     */
    private static final int OPFOR_GUNNERY = OrbitalSupport.DEFAULT_GUNNERY;
}
