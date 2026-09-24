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

import java.util.ArrayList;
import java.util.List;

import megamek.common.enums.SkillLevel;
import mekhq.campaign.enums.DragoonRating;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.EnemyData;
import mekhq.campaign.mission.contract.contractData.ContractMoraleLevel;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;
import mekhq.campaign.mission.utilities.OrbitalControlCalculator.OrbitalControl;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OrbitalControlCalculator}: how the orbital space over a contract is divided, what moves it, and
 * that the roll which settles a scenario reads that division as written.
 */
class OrbitalControlCalculatorTest {

    @Test
    void noContractSplitsTheSkyEvenly() {
        OrbitalControl control = OrbitalControlCalculator.forContract(null);

        assertEquals(40, control.playerChance());
        assertEquals(40, control.enemyChance());
    }

    @Test
    void freshContractSplitsTheSkyEvenly() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.STALEMATE));

        assertEquals(40, control.playerChance());
        assertEquals(40, control.enemyChance());
    }

    @Test
    void aStalematedEnemyIsMovedNeitherWay() {
        assertEquals(0, OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.STALEMATE));
    }

    @Test
    void failingMoraleTakesTheEnemyDownToTheFloor() {
        assertEquals(-7, OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.WEAKENED));
        assertEquals(-13, OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.CRITICAL));
        assertEquals(-OrbitalControlCalculator.MAXIMUM_MORALE_MODIFIER,
              OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.ROUTED));
    }

    @Test
    void risingMoraleTakesTheEnemyUpToTheCap() {
        assertEquals(7, OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.ADVANCING));
        assertEquals(13, OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.DOMINATING));
        assertEquals(OrbitalControlCalculator.MAXIMUM_MORALE_MODIFIER,
              OrbitalControlCalculator.moraleModifier(ContractMoraleLevel.OVERWHELMING));
    }

    @Test
    void nullMoraleMovesNothing() {
        assertEquals(0, OrbitalControlCalculator.moraleModifier(null));
    }

    @Test
    void aRoutedEnemyIsLessLikelyToHoldOrbit() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.ROUTED));

        assertEquals(40, control.playerChance());
        assertEquals(20, control.enemyChance());
    }

    @Test
    void overwhelmingMoraleLiftsTheEnemyWithoutTouchingThePlayer() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.OVERWHELMING));

        assertEquals(40, control.playerChance());
        assertEquals(60, control.enemyChance());
    }

    @Test
    void equipmentRatingMovesTheEnemyByItsOwnScale() {
        assertEquals(-50, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_F));
        assertEquals(-20, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_D));
        assertEquals(-10, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_C));
        assertEquals(0, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_B));
        assertEquals(20, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_A));
        assertEquals(40, OrbitalControlCalculator.equipmentModifier(DragoonRating.DRAGOON_ASTAR));
    }

    @Test
    void anUnknownRatingMovesNothing() {
        assertEquals(0, OrbitalControlCalculator.equipmentModifier((DragoonRating) null));
        assertEquals(0, OrbitalControlCalculator.equipmentModifier((AbstractContract) null));
    }

    @Test
    void aContractWithNoEnemyRecordedMovesNothing() {
        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getMoraleLevel()).thenReturn(ContractMoraleLevel.STALEMATE);
        when(contract.getEnemyData()).thenReturn(null);
        when(contract.getScenarios()).thenReturn(List.of());

        assertEquals(40, OrbitalControlCalculator.forContract(contract).enemyChance());
    }

    @Test
    void anFRatedEnemyHasNothingOverhead() {
        // -50 against a base of 40: a force that cannot keep its BattleMechs running has no WarShip either.
        OrbitalControl control = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_F));

        assertEquals(0, control.enemyChance());
        assertEquals(40, control.playerChance());
    }

    @Test
    void anFRatedEnemyOnAWinningStreakScrapesSomethingTogether() {
        // The modifiers add rather than F being a veto: 40 - 50 + 20 leaves a tenth of the sky to a force whose
        // campaign is going well enough to have borrowed a hull from someone.
        assertEquals(10,
              OrbitalControlCalculator.forContract(
                          contract(ContractMoraleLevel.OVERWHELMING, DragoonRating.DRAGOON_F))
                    .enemyChance());
    }

    @Test
    void aPoorlyEquippedEnemyIsLessLikelyToHoldOrbit() {
        assertEquals(20,
              OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_D))
                    .enemyChance());
        assertEquals(30,
              OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_C))
                    .enemyChance());
    }

    @Test
    void aWellEquippedEnemyIsMoreLikelyToHoldOrbit() {
        OrbitalControl control = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_A));

        assertEquals(60, control.enemyChance());
    }

    @Test
    void equipmentAndMoraleAddTogether() {
        // D is -20 and Advancing is +7, so a well-motivated but poorly equipped enemy still ends up behind.
        assertEquals(27,
              OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.ADVANCING, DragoonRating.DRAGOON_D))
                    .enemyChance());
    }

    @Test
    void theEnemysChanceStopsShortOfCertainty() {
        // A* is +40 and Overwhelming is +20, which would ask for 100. Orbit is never a certainty for either side.
        OrbitalControl control = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.OVERWHELMING, DragoonRating.DRAGOON_ASTAR));

        assertEquals(OrbitalControlCalculator.MAXIMUM_CHANCE, control.enemyChance());
        // The two tracks are independent, so nothing the enemy does moves the player's.
        assertEquals(40, control.playerChance());
    }

    @Test
    void aWellEquippedEnemyIsWorthMoreThanAMerelyGoodOne() {
        // Under the old contested sky both of these hit the same ceiling; on their own track they no longer do.
        int rated_A = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_A)).enemyChance();
        int rated_ASTAR = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.STALEMATE, DragoonRating.DRAGOON_ASTAR)).enemyChance();

        assertEquals(60, rated_A);
        assertEquals(80, rated_ASTAR);
    }

    @Test
    void wonSpaceBattleMovesTheSkyToThePlayer() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY));

        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertEquals(50, control.playerChance());
        assertEquals(30, control.enemyChance());
    }

    @Test
    void lostSpaceBattleMovesTheSkyToTheEnemy() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_DEFEAT));

        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertEquals(30, control.playerChance());
        assertEquals(50, control.enemyChance());
    }

    @Test
    void drawnSpaceBattleSettlesNothing() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE, spaceScenario(ScenarioStatus.DRAW));

        assertEquals(40, OrbitalControlCalculator.forContract(contract).playerChance());
    }

    @Test
    void groundBattlesDoNotMoveTheSky() {
        Scenario ground = mock(Scenario.class);
        when(ground.getBoardType()).thenReturn(Scenario.T_GROUND);
        when(ground.getStatus()).thenReturn(ScenarioStatus.DECISIVE_VICTORY);

        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE, ground);

        assertEquals(40, OrbitalControlCalculator.forContract(contract).playerChance());
    }

    @Test
    void unfoughtSpaceBattlesSettleNothing() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE, spaceScenario(ScenarioStatus.CURRENT));

        assertEquals(40, OrbitalControlCalculator.forContract(contract).playerChance());
    }

    @Test
    void spaceBattlesAccumulate() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.VICTORY),
              spaceScenario(ScenarioStatus.MARGINAL_VICTORY),
              spaceScenario(ScenarioStatus.DEFEAT));

        assertEquals(OrbitalControlCalculator.SPACE_BATTLE_SHIFT,
              OrbitalControlCalculator.spaceBattleShift(contract));
    }

    @Test
    void thePlayerCannotBeSqueezedOutByMorale() {
        // Six wins is more than the whole sky; the enemy's morale bonus is what gives way, not the player's wins.
        List<Scenario> wins = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            wins.add(spaceScenario(ScenarioStatus.DECISIVE_VICTORY));
        }

        AbstractContract contract = contract(ContractMoraleLevel.OVERWHELMING, wins.toArray(new Scenario[0]));
        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertEquals(OrbitalControlCalculator.MAXIMUM_CHANCE, control.playerChance());
        assertEquals(0, control.enemyChance());
    }

    @Test
    void bothSidesCanBeOverheadAtOnce() {
        // A well-equipped enemy in good spirits reaches 80 while the player keeps their own 40. Under the old
        // contested sky that was impossible; on separate tracks it is an ordinary scenario.
        OrbitalControl control = OrbitalControlCalculator.forContract(
              contract(ContractMoraleLevel.OVERWHELMING, DragoonRating.DRAGOON_A));

        assertEquals(40, control.playerChance());
        assertEquals(80, control.enemyChance());
        assertTrue(control.playerChance() + control.enemyChance() > 100,
              "expected the two independent tracks to be free to exceed 100 between them");
    }

    @Test
    void neitherSideCanEverBeCertainOfOrbit() {
        assertEquals(95, OrbitalControlCalculator.MAXIMUM_CHANCE);
        assertEquals(95, OrbitalControlCalculator.chance(100));
        assertEquals(95, OrbitalControlCalculator.chance(500));
        assertEquals(0, OrbitalControlCalculator.chance(-500));
        assertEquals(40, OrbitalControlCalculator.chance(40));
    }

    @Test
    void aCertainChanceAlwaysHoldsOrbit() {
        assertTrue(OrbitalControlCalculator.holdsOrbit(100));
    }

    @Test
    void aZeroChanceNeverHoldsOrbit() {
        assertFalse(OrbitalControlCalculator.holdsOrbit(0));
        assertFalse(OrbitalControlCalculator.holdsOrbit(-10));
    }

    @Test
    void anEvenChanceEventuallyFallsBothWays() {
        boolean held = false;
        boolean missed = false;

        // A 40% chance over a thousand rolls: a run that never lands on one side is a bug, not bad luck.
        for (int i = 0; i < 1000; i++) {
            if (OrbitalControlCalculator.holdsOrbit(40)) {
                held = true;
            } else {
                missed = true;
            }
        }

        assertTrue(held && missed, "a 40% chance never produced one of its two outcomes");
    }

    private static Scenario spaceScenario(ScenarioStatus status) {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getBoardType()).thenReturn(Scenario.T_SPACE);
        when(scenario.getStatus()).thenReturn(status);
        return scenario;
    }

    /** A contract whose enemy is equipment rating B, so only the morale and space-battle terms are in play. */
    private static AbstractContract contract(ContractMoraleLevel moraleLevel, Scenario... scenarios) {
        return contract(moraleLevel, DragoonRating.DRAGOON_B, scenarios);
    }

    private static AbstractContract contract(ContractMoraleLevel moraleLevel, DragoonRating rating,
          Scenario... scenarios) {
        // Built before the stubbing starts: a value created inside thenReturn() can leave the outer when() unfinished.
        EnemyData enemy = enemyRated(rating);
        List<Scenario> fought = List.of(scenarios);

        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getMoraleLevel()).thenReturn(moraleLevel);
        when(contract.getEnemyData()).thenReturn(enemy);
        when(contract.getScenarios()).thenReturn(fought);
        return contract;
    }

    private static EnemyData enemyRated(DragoonRating rating) {
        EnemyData enemy = new EnemyData("LA", null, "Opposing Force", null, null);
        return new EnemyData(enemy, SkillLevel.REGULAR, rating.getRating());
    }
}
