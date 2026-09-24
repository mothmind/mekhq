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
package mekhq.campaign.mission.utilities;

import java.util.Map;

import megamek.common.compute.Compute;
import megamek.common.annotations.Nullable;
import mekhq.campaign.enums.DragoonRating;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.ContractMoraleLevel;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;

/**
 * Works out which side holds the orbital space over a battlefield, under the contested method.
 *
 * <p>Each side keeps its own chance of having a ship on station, rolled separately, so a battle can be fought
 * under both fleets, one, or neither. Both start a contract on even terms. The enemy's chance moves with what they
 * can afford and how their war is going - their equipment rating and their morale - and both move with the outcome
 * of any space battle fought during the contract: win the void and the guns overhead become yours while theirs are
 * driven off, lose it and the reverse.</p>
 *
 * <p>Neither side can be certain of orbit. A chance is held to {@value #MAXIMUM_CHANCE} however well a campaign is
 * going, because a ship can still be out of position or an order can still fail to arrive.</p>
 *
 * @author mothmind
 */
public class OrbitalControlCalculator {

    private OrbitalControlCalculator() {
    }

    /** The share each side starts a contract with, before anything moves it. */
    public static final int BASE_CHANCE = 40;

    /**
     * The most either side's chance can reach. Orbit is never a certainty: a ship can be out of position, the
     * firing window can close, the orders can fail to arrive. The last twenty-first of the sky belongs to that.
     */
    public static final int MAXIMUM_CHANCE = 95;

    /**
     * The most the enemy's morale can move their share, in either direction: twenty points to a force whose
     * campaign is going perfectly, and twenty off one that has been routed.
     */
    public static final int MAXIMUM_MORALE_MODIFIER = 20;

    /**
     * How far one space battle moves the balance. A win takes these points off the enemy and gives them to the
     * player; a loss does the reverse. Four decisive engagements are therefore enough to take orbit outright.
     */
    public static final int SPACE_BATTLE_SHIFT = 10;

    /**
     * What the opposing force's equipment rating is worth to them in orbit, in percentage points.
     *
     * <p>A WarShip is the most expensive thing a force can field, so the rating that measures what a force can
     * afford tells heavily here - more heavily than morale does. An F-rated outfit is scraping for working
     * BattleMechs and has nothing overhead at all; the F modifier is deliberately large enough to take such a force
     * to zero on its own, whatever else is going for them.</p>
     */
    public static final Map<DragoonRating, Integer> EQUIPMENT_MODIFIERS = Map.of(
          DragoonRating.DRAGOON_F, -50,
          DragoonRating.DRAGOON_D, -20,
          DragoonRating.DRAGOON_C, -10,
          DragoonRating.DRAGOON_B, 0,
          DragoonRating.DRAGOON_A, 20,
          DragoonRating.DRAGOON_ASTAR, 40);

    /**
     * Each side's own percentage chance of having a ship in a firing position this scenario.
     *
     * <p>The two are independent, not two halves of one hundred: each side keeps its own fleet and its own orders,
     * and a scenario can see both bombarding, one, or neither.</p>
     *
     * @param playerChance The player's chance
     * @param enemyChance  The opposing force's chance
     */
    public record OrbitalControl(int playerChance, int enemyChance) {
    }

    /**
     * Works out each side's chance of having a ship overhead during this contract.
     *
     * <p>A contract that has not been fought over yet starts both sides at {@value #BASE_CHANCE}. From there the
     * enemy's chance moves with their equipment rating and their morale, and every space battle moves both: a win
     * raises the player's chance and lowers the enemy's, a loss does the reverse.</p>
     *
     * @param contract The contract the scenario belongs to, or null when there is none to read
     *
     * @return Each side's current chance
     */
    public static OrbitalControl forContract(@Nullable AbstractContract contract) {
        if (contract == null) {
            return new OrbitalControl(BASE_CHANCE, BASE_CHANCE);
        }

        int shift = spaceBattleShift(contract);

        return new OrbitalControl(chance(BASE_CHANCE + shift),
              chance(BASE_CHANCE
                    + moraleModifier(contract.getMoraleLevel())
                    + equipmentModifier(contract)
                    - shift));
    }

    /** @return A chance held to its bounds: never negative, never a certainty */
    public static int chance(int value) {
        return Math.clamp(value, 0, MAXIMUM_CHANCE);
    }

    /**
     * Rolls one side's chance of having a ship in a firing position.
     *
     * <p>Each side is rolled on its own, so a scenario can see both fleets overhead or neither.</p>
     *
     * @param chance That side's percentage chance
     *
     * @return True when that side can fire this scenario
     */
    public static boolean holdsOrbit(int chance) {
        return (chance > 0) && (Compute.randomInt(100) < chance);
    }

    /**
     * Reads what the enemy's morale is worth to them in orbit.
     *
     * <p>Morale cuts both ways. A force whose campaign is going well presses its advantage overhead as well as on
     * the ground; one that is being beaten has other calls on its navy, and a routed force is not holding anything.
     * The modifier grows evenly with each step of morale either side of a stalemate, reaching its cap at
     * Overwhelming and its floor at Routed.</p>
     *
     * @param moraleLevel The opposing force's current morale
     *
     * @return The points this morale moves the enemy's share by, within plus or minus
     *       {@link #MAXIMUM_MORALE_MODIFIER}
     */
    public static int moraleModifier(@Nullable ContractMoraleLevel moraleLevel) {
        if (moraleLevel == null) {
            return 0;
        }

        int level = moraleLevel.getLevel();
        int modifier = Math.round((level * (float) MAXIMUM_MORALE_MODIFIER)
              / ContractMoraleLevel.MAXIMUM_MORALE_LEVEL);
        return Math.clamp(modifier, -MAXIMUM_MORALE_MODIFIER, MAXIMUM_MORALE_MODIFIER);
    }

    /**
     * Reads what the opposing force's equipment rating is worth to them in orbit.
     *
     * @param contract The contract whose enemy is read
     *
     * @return The points this rating moves the enemy's share by, or zero when the contract records no enemy
     */
    public static int equipmentModifier(@Nullable AbstractContract contract) {
        if ((contract == null) || (contract.getEnemyData() == null)) {
            return 0;
        }

        return equipmentModifier(DragoonRating.fromRating(contract.getEnemyData().equipmentRating()));
    }

    /**
     * @param rating The opposing force's equipment rating
     *
     * @return The points this rating moves the enemy's share by. An unrecognised rating moves nothing rather than
     *       guessing, so a rating scale that grows later does not silently start penalising anyone.
     */
    public static int equipmentModifier(@Nullable DragoonRating rating) {
        return (rating == null) ? 0 : EQUIPMENT_MODIFIERS.getOrDefault(rating, 0);
    }

    /**
     * Totals what the contract's space battles have done to the balance.
     *
     * <p>Every finished scenario fought in space counts, whether the player set it up themselves or it was generated
     * for them. A draw settles nothing and is passed over; a refused engagement or a fleet kept in being counts
     * against the player, since the enemy is left holding the space either way.</p>
     *
     * @param contract The contract whose scenarios are read
     *
     * @return The points to move from the enemy to the player, negative when the enemy is ahead
     */
    public static int spaceBattleShift(AbstractContract contract) {
        int shift = 0;

        for (Scenario scenario : contract.getScenarios()) {
            if (scenario.getBoardType() != Scenario.T_SPACE) {
                continue;
            }

            ScenarioStatus status = scenario.getStatus();
            if (status.isOverallVictory()) {
                shift += SPACE_BATTLE_SHIFT;
            } else if (status.isOverallDefeat()) {
                shift -= SPACE_BATTLE_SHIFT;
            }
        }

        return shift;
    }
}
