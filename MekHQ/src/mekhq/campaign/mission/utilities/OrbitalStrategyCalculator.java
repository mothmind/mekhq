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

import java.util.EnumMap;
import java.util.Map;

import megamek.common.annotations.Nullable;
import megamek.common.compute.Compute;
import megamek.common.enums.SkillLevel;
import mekhq.campaign.enums.DragoonRating;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.personnel.skills.SkillType;

/**
 * Decides who has a ship in a firing position by asking the people involved, rather than by rolling a percentage.
 *
 * <p>Getting a capital ship into a firing window over a moving battle is a staff problem: it is the work of the
 * officer who planned the operation, not a property of the contract. So each side rolls 2D6 against a target number
 * taken from a skill - the vessel's commander's Strategy for the player, the opposing force's overall skill for the
 * enemy - and must meet or beat it, the way most BattleTech skills are rolled. Double ones always fails, however
 * generous the modifiers.</p>
 *
 * <p>Modifiers move the roll, never the target. A commander does not become worse at their job because the last
 * space battle went badly; the window is simply harder to make.</p>
 *
 * @author mothmind
 */
public class OrbitalStrategyCalculator {

    private OrbitalStrategyCalculator() {
    }

    /**
     * Target numbers by the opposing force's overall skill, for a force with no named officer to read a Strategy
     * skill from.
     *
     * <p>Ultra-Green needs a 9 and Legendary a 3, one point per step of skill the whole way. A force with no skill
     * recorded rolls as Ultra-Green rather than worse: there is no rung below the bottom of the ladder.</p>
     */
    public static final Map<SkillLevel, Integer> ENEMY_TARGET_NUMBERS = new EnumMap<>(Map.of(
          SkillLevel.NONE, 9,
          SkillLevel.ULTRA_GREEN, 9,
          SkillLevel.GREEN, 8,
          SkillLevel.REGULAR, 7,
          SkillLevel.VETERAN, 6,
          SkillLevel.ELITE, 5,
          SkillLevel.HEROIC, 4,
          SkillLevel.LEGENDARY, 3));

    /**
     * The percentage chance that the opposing force has a ship overhead at all, by equipment rating, before anyone
     * rolls for a firing position.
     *
     * <p>A WarShip is the single most expensive thing a force can own, so under this method the rating gates
     * availability outright rather than nudging a percentage. An F-rated outfit has no capital ship to send and
     * never will; an A*-rated one always has a hull on station, and the only question is whether its crew can make
     * the window.</p>
     */
    public static final Map<DragoonRating, Integer> ENEMY_SHIP_AVAILABILITY = new EnumMap<>(Map.of(
          DragoonRating.DRAGOON_F, 0,
          DragoonRating.DRAGOON_D, 1,
          DragoonRating.DRAGOON_C, 15,
          DragoonRating.DRAGOON_B, 30,
          DragoonRating.DRAGOON_A, 60,
          DragoonRating.DRAGOON_ASTAR, 100));

    /** The target number used when a vessel's commander has no Strategy skill to roll against. */
    public static final int UNTRAINED_TARGET_NUMBER = 9;

    /** A decisive space battle is worth this much to the winner, and costs the loser the same. */
    public static final int DECISIVE_SPACE_BATTLE_MODIFIER = 2;

    /** Any other win or loss in space is worth this much. */
    public static final int SPACE_BATTLE_MODIFIER = 1;

    /**
     * The chance, in whole percent, that 2D6 meets or beats each target number from 2 upwards, with double ones
     * failing regardless. Index 2 is a target of 2, index 13 an impossible one.
     */
    private static final int[] ODDS_BY_TARGET = { 0, 0, 97, 97, 92, 83, 72, 58, 42, 28, 17, 8, 3, 0 };

    /**
     * @param targetNumber The number to meet or beat before modifiers
     * @param modifier     The modifier on the roll, positive being help
     *
     * @return The chance of making that roll, in whole percent
     */
    public static int oddsOf(int targetNumber, int modifier) {
        int needed = targetNumber - modifier;
        return ODDS_BY_TARGET[Math.clamp(needed, 2, 13)];
    }

    /**
     * Rolls 2D6 against a target number, the way BattleTech rolls a skill: meet it or beat it.
     *
     * <p>Double ones fails whatever the modifiers say. A staff can be well briefed, well rested and well ahead of
     * the enemy and still have the ship in the wrong place when the call comes.</p>
     *
     * @param targetNumber The number to meet or beat before modifiers
     * @param modifier     The modifier on the roll, positive being help
     *
     * @return True when the roll succeeds
     */
    public static boolean rollAgainst(int targetNumber, int modifier) {
        int first = Compute.d6();
        int second = Compute.d6();
        if ((first == 1) && (second == 1)) {
            return false;
        }

        return (first + second + modifier) >= targetNumber;
    }

    /**
     * Reads the target number a vessel's commander rolls against.
     *
     * <p>The Strategy skill's final value is used rather than its raw level, so the officer's SPAs, their injuries
     * and anything else currently acting on them all count - a commander in the infirmary plans a worse operation
     * than the same commander fit.</p>
     *
     * @param commander The vessel's commanding officer, or null when it has none
     *
     * @return The number 2D6 must meet or beat
     */
    public static int playerTargetNumber(@Nullable Person commander) {
        if (commander == null) {
            return UNTRAINED_TARGET_NUMBER;
        }

        Skill strategy = commander.getSkill(SkillType.S_STRATEGY);
        if (strategy == null) {
            return UNTRAINED_TARGET_NUMBER;
        }

        return strategy.getFinalSkillValue(commander.getSkillModifierData());
    }

    /**
     * @param forceSkill The opposing force's overall skill
     *
     * @return The number their 2D6 must meet or beat
     */
    public static int enemyTargetNumber(@Nullable SkillLevel forceSkill) {
        return (forceSkill == null)
              ? UNTRAINED_TARGET_NUMBER
              : ENEMY_TARGET_NUMBERS.getOrDefault(forceSkill, UNTRAINED_TARGET_NUMBER);
    }

    /**
     * Decides whether the opposing force has a capital ship overhead at all this scenario.
     *
     * @param contract The contract whose enemy is read
     *
     * @return True when a ship is on station and worth rolling for
     */
    public static boolean enemyShipPresent(@Nullable AbstractContract contract) {
        int chance = enemyShipChance(contract);
        return (chance >= 100) || ((chance > 0) && (Compute.randomInt(100) < chance));
    }

    /**
     * @param contract The contract whose enemy is read
     *
     * @return The percentage chance the opposing force has a capital ship at all, zero when there is no enemy
     *       recorded to read a rating from
     */
    public static int enemyShipChance(@Nullable AbstractContract contract) {
        if ((contract == null) || (contract.getEnemyData() == null)) {
            return 0;
        }

        DragoonRating rating = DragoonRating.fromRating(contract.getEnemyData().equipmentRating());
        return ENEMY_SHIP_AVAILABILITY.getOrDefault(rating, 0);
    }

    /**
     * Totals the modifiers on one side's roll.
     *
     * @param contract The contract being fought, or null when there is none to read
     * @param forPlayer True for the player's roll, false for the opposing force's
     *
     * @return The modifier to add to that side's 2D6
     */
    public static int rollModifier(@Nullable AbstractContract contract, boolean forPlayer) {
        if (contract == null) {
            return 0;
        }

        return spaceBattleModifier(contract, forPlayer) + moraleModifier(contract, forPlayer);
    }

    /**
     * Totals what the contract's space battles are worth to one side.
     *
     * <p>Every finished scenario fought in space counts, and what helps one side by the same token hinders the
     * other: control of the approaches is not something both fleets can have.</p>
     *
     * @param contract  The contract whose scenarios are read
     * @param forPlayer True for the player's roll, false for the opposing force's
     *
     * @return The modifier those battles are worth to that side
     */
    public static int spaceBattleModifier(AbstractContract contract, boolean forPlayer) {
        int modifier = 0;

        for (Scenario scenario : contract.getScenarios()) {
            if (scenario.getBoardType() != Scenario.T_SPACE) {
                continue;
            }

            ScenarioStatus status = scenario.getStatus();
            if (status.isOverallVictory()) {
                modifier += status.isDecisiveVictory() ? DECISIVE_SPACE_BATTLE_MODIFIER : SPACE_BATTLE_MODIFIER;
            } else if (status.isOverallDefeat()) {
                modifier -= status.isDecisiveDefeat() ? DECISIVE_SPACE_BATTLE_MODIFIER : SPACE_BATTLE_MODIFIER;
            }
        }

        return forPlayer ? modifier : -modifier;
    }

    /**
     * Reads what the opposing force's morale is worth.
     *
     * <p>Morale only ever helps, and only ever helps one side: a confident enemy gets their own ship where it needs
     * to be, and a demoralised one leaves the window open for the player instead. Neither side is penalised for the
     * other's state of mind, which is why this is read as two one-sided bonuses rather than one scale.</p>
     *
     * @param contract  The contract whose enemy morale is read
     * @param forPlayer True for the player's roll, false for the opposing force's
     *
     * @return The modifier morale is worth to that side, never negative
     */
    public static int moraleModifier(AbstractContract contract, boolean forPlayer) {
        if (contract.getMoraleLevel() == null) {
            return 0;
        }

        int level = contract.getMoraleLevel().getLevel();
        if (forPlayer) {
            return Math.max(0, -level);
        }
        return Math.max(0, level);
    }
}
