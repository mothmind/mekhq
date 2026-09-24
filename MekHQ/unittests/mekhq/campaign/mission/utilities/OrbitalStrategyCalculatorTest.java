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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.common.enums.SkillLevel;
import mekhq.campaign.enums.DragoonRating;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.ContractMoraleLevel;
import mekhq.campaign.mission.contract.contractData.EnemyData;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OrbitalStrategyCalculator}: the target numbers each side rolls against, what moves those rolls,
 * and whether the opposing force has a capital ship to roll for in the first place.
 */
class OrbitalStrategyCalculatorTest {

    @Test
    void theEnemyScaleRunsFromUltraGreenToLegendary() {
        assertEquals(9, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.ULTRA_GREEN));
        assertEquals(8, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.GREEN));
        assertEquals(7, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.REGULAR));
        assertEquals(6, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.VETERAN));
        assertEquals(5, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.ELITE));
        assertEquals(4, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.HEROIC));
        assertEquals(3, OrbitalStrategyCalculator.enemyTargetNumber(SkillLevel.LEGENDARY));
    }

    @Test
    void theEnemyScaleIsOnePointPerStep() {
        SkillLevel[] ladder = { SkillLevel.ULTRA_GREEN, SkillLevel.GREEN, SkillLevel.REGULAR, SkillLevel.VETERAN,
                                SkillLevel.ELITE, SkillLevel.HEROIC, SkillLevel.LEGENDARY };

        for (int rung = 1; rung < ladder.length; rung++) {
            assertEquals(OrbitalStrategyCalculator.enemyTargetNumber(ladder[rung - 1]) - 1,
                  OrbitalStrategyCalculator.enemyTargetNumber(ladder[rung]),
                  ladder[rung] + " is not one point better than " + ladder[rung - 1]);
        }
    }

    @Test
    void anUnknownEnemySkillRollsAsUntrained() {
        assertEquals(OrbitalStrategyCalculator.UNTRAINED_TARGET_NUMBER,
              OrbitalStrategyCalculator.enemyTargetNumber(null));
    }

    @Test
    void aCommanderWithoutStrategyRollsAsUntrained() {
        assertEquals(OrbitalStrategyCalculator.UNTRAINED_TARGET_NUMBER,
              OrbitalStrategyCalculator.playerTargetNumber(null));
    }

    @Test
    void equipmentRatingDecidesWhetherTheEnemyOwnsAShipAtAll() {
        assertEquals(0, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_F)));
        assertEquals(1, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_D)));
        assertEquals(15, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_C)));
        assertEquals(30, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_B)));
        assertEquals(60, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_A)));
        assertEquals(100, OrbitalStrategyCalculator.enemyShipChance(contractRated(DragoonRating.DRAGOON_ASTAR)));
    }

    @Test
    void anFRatedEnemyNeverHasAShipToRollFor() {
        AbstractContract contract = contractRated(DragoonRating.DRAGOON_F);
        for (int i = 0; i < 200; i++) {
            assertFalse(OrbitalStrategyCalculator.enemyShipPresent(contract));
        }
    }

    @Test
    void anAStarRatedEnemyAlwaysHasAShipToRollFor() {
        AbstractContract contract = contractRated(DragoonRating.DRAGOON_ASTAR);
        for (int i = 0; i < 200; i++) {
            assertTrue(OrbitalStrategyCalculator.enemyShipPresent(contract));
        }
    }

    @Test
    void aContractWithNoEnemyRecordedHasNoShip() {
        assertEquals(0, OrbitalStrategyCalculator.enemyShipChance(null));
        assertFalse(OrbitalStrategyCalculator.enemyShipPresent(null));
    }

    @Test
    void doubleOnesAlwaysFails() {
        // A target of 2 with a large bonus is otherwise unmissable; over this many rolls snake eyes must show up.
        boolean failed = false;
        for (int i = 0; i < 2000; i++) {
            if (!OrbitalStrategyCalculator.rollAgainst(2, 10)) {
                failed = true;
                break;
            }
        }

        assertTrue(failed, "a roll that can only fail on double ones never failed in 2000 attempts");
    }

    @Test
    void anImpossibleTargetIsNeverMade() {
        for (int i = 0; i < 500; i++) {
            assertFalse(OrbitalStrategyCalculator.rollAgainst(13, 0));
        }
    }

    @Test
    void theOddsMatchTheDiceTheyStandFor() {
        // Worked from the 36 outcomes of 2D6, with double ones failing whatever the target.
        assertEquals(97, OrbitalStrategyCalculator.oddsOf(2, 0));
        assertEquals(97, OrbitalStrategyCalculator.oddsOf(3, 0));
        assertEquals(92, OrbitalStrategyCalculator.oddsOf(4, 0));
        assertEquals(83, OrbitalStrategyCalculator.oddsOf(5, 0));
        assertEquals(72, OrbitalStrategyCalculator.oddsOf(6, 0));
        assertEquals(58, OrbitalStrategyCalculator.oddsOf(7, 0));
        assertEquals(42, OrbitalStrategyCalculator.oddsOf(8, 0));
        assertEquals(28, OrbitalStrategyCalculator.oddsOf(9, 0));
        assertEquals(17, OrbitalStrategyCalculator.oddsOf(10, 0));
        assertEquals(8, OrbitalStrategyCalculator.oddsOf(11, 0));
        assertEquals(3, OrbitalStrategyCalculator.oddsOf(12, 0));
        assertEquals(0, OrbitalStrategyCalculator.oddsOf(13, 0));
    }

    @Test
    void theOddsNeverReachCertaintyBecauseOfDoubleOnes() {
        assertEquals(97, OrbitalStrategyCalculator.oddsOf(7, 20));
    }

    @Test
    void modifiersMoveTheOddsNotTheTarget() {
        assertEquals(OrbitalStrategyCalculator.oddsOf(5, 0), OrbitalStrategyCalculator.oddsOf(7, 2));
        assertEquals(OrbitalStrategyCalculator.oddsOf(9, 0), OrbitalStrategyCalculator.oddsOf(7, -2));
    }

    @Test
    void anImpossibleTargetShowsAsNoChance() {
        assertEquals(0, OrbitalStrategyCalculator.oddsOf(20, 0));
    }

    @Test
    void aWonSpaceBattleHelpsThePlayerAndHindersTheEnemy() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE, spaceScenario(ScenarioStatus.VICTORY));

        assertEquals(1, OrbitalStrategyCalculator.spaceBattleModifier(contract, true));
        assertEquals(-1, OrbitalStrategyCalculator.spaceBattleModifier(contract, false));
    }

    @Test
    void aDecisiveSpaceBattleIsWorthDouble() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY));

        assertEquals(2, OrbitalStrategyCalculator.spaceBattleModifier(contract, true));
        assertEquals(-2, OrbitalStrategyCalculator.spaceBattleModifier(contract, false));
    }

    @Test
    void aLostSpaceBattleReversesIt() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_DEFEAT));

        assertEquals(-2, OrbitalStrategyCalculator.spaceBattleModifier(contract, true));
        assertEquals(2, OrbitalStrategyCalculator.spaceBattleModifier(contract, false));
    }

    @Test
    void aDrawnSpaceBattleMovesNeitherSide() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE, spaceScenario(ScenarioStatus.DRAW));

        assertEquals(0, OrbitalStrategyCalculator.spaceBattleModifier(contract, true));
        assertEquals(0, OrbitalStrategyCalculator.spaceBattleModifier(contract, false));
    }

    @Test
    void aGroundBattleMovesNeitherSide() {
        Scenario ground = mock(Scenario.class);
        when(ground.getBoardType()).thenReturn(Scenario.T_GROUND);
        when(ground.getStatus()).thenReturn(ScenarioStatus.DECISIVE_VICTORY);

        assertEquals(0,
              OrbitalStrategyCalculator.spaceBattleModifier(contract(ContractMoraleLevel.STALEMATE, ground), true));
    }

    @Test
    void spaceBattlesAccumulate() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY),
              spaceScenario(ScenarioStatus.VICTORY),
              spaceScenario(ScenarioStatus.DEFEAT));

        assertEquals(2, OrbitalStrategyCalculator.spaceBattleModifier(contract, true));
    }

    @Test
    void confidentEnemyMoraleHelpsOnlyTheEnemy() {
        AbstractContract contract = contract(ContractMoraleLevel.OVERWHELMING);

        assertEquals(3, OrbitalStrategyCalculator.moraleModifier(contract, false));
        assertEquals(0, OrbitalStrategyCalculator.moraleModifier(contract, true));
    }

    @Test
    void brokenEnemyMoraleHelpsOnlyThePlayer() {
        AbstractContract contract = contract(ContractMoraleLevel.ROUTED);

        assertEquals(3, OrbitalStrategyCalculator.moraleModifier(contract, true));
        assertEquals(0, OrbitalStrategyCalculator.moraleModifier(contract, false));
    }

    @Test
    void aStalemateHelpsNeitherSide() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE);

        assertEquals(0, OrbitalStrategyCalculator.moraleModifier(contract, true));
        assertEquals(0, OrbitalStrategyCalculator.moraleModifier(contract, false));
    }

    @Test
    void moraleAndSpaceBattlesAddTogether() {
        // Advancing is +1 to the enemy; a decisive loss in space costs them 2 more than it costs the player.
        AbstractContract contract = contract(ContractMoraleLevel.ADVANCING,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY));

        assertEquals(2, OrbitalStrategyCalculator.rollModifier(contract, true));
        assertEquals(-1, OrbitalStrategyCalculator.rollModifier(contract, false));
    }

    @Test
    void noContractMovesNothing() {
        assertEquals(0, OrbitalStrategyCalculator.rollModifier(null, true));
        assertEquals(0, OrbitalStrategyCalculator.rollModifier(null, false));
    }

    private static Scenario spaceScenario(ScenarioStatus status) {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getBoardType()).thenReturn(Scenario.T_SPACE);
        when(scenario.getStatus()).thenReturn(status);
        return scenario;
    }

    private static AbstractContract contractRated(DragoonRating rating) {
        EnemyData enemy = new EnemyData("LA", null, "Opposing Force", null, null);
        EnemyData rated = new EnemyData(enemy, SkillLevel.REGULAR, rating.getRating());

        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getEnemyData()).thenReturn(rated);
        return contract;
    }

    private static AbstractContract contract(ContractMoraleLevel moraleLevel, Scenario... scenarios) {
        List<Scenario> fought = List.of(scenarios);

        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getMoraleLevel()).thenReturn(moraleLevel);
        when(contract.getScenarios()).thenReturn(fought);
        return contract;
    }
}
