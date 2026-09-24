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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.ContractMoraleLevel;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;
import mekhq.campaign.mission.utilities.OrbitalControlCalculator.OrbitalControl;
import mekhq.campaign.mission.utilities.OrbitalControlCalculator.OrbitalHolder;
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
        assertEquals(20, control.neitherChance());
    }

    @Test
    void freshContractSplitsTheSkyEvenly() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.STALEMATE));

        assertEquals(40, control.playerChance());
        assertEquals(40, control.enemyChance());
        assertEquals(20, control.neitherChance());
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
    void aRoutedEnemyWidensTheGapTheyCannotFireThrough() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.ROUTED));

        assertEquals(40, control.playerChance());
        assertEquals(20, control.enemyChance());
        assertEquals(40, control.neitherChance());
    }

    @Test
    void overwhelmingMoraleLeavesNoNeutralShare() {
        OrbitalControl control = OrbitalControlCalculator.forContract(contract(ContractMoraleLevel.OVERWHELMING));

        assertEquals(40, control.playerChance());
        assertEquals(60, control.enemyChance());
        assertEquals(0, control.neitherChance());
    }

    @Test
    void wonSpaceBattleMovesTheSkyToThePlayer() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY));

        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertEquals(50, control.playerChance());
        assertEquals(30, control.enemyChance());
        assertEquals(20, control.neitherChance());
    }

    @Test
    void lostSpaceBattleMovesTheSkyToTheEnemy() {
        AbstractContract contract = contract(ContractMoraleLevel.STALEMATE,
              spaceScenario(ScenarioStatus.DECISIVE_DEFEAT));

        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertEquals(30, control.playerChance());
        assertEquals(50, control.enemyChance());
        assertEquals(20, control.neitherChance());
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

        assertEquals(100, control.playerChance());
        assertEquals(0, control.enemyChance());
        assertEquals(0, control.neitherChance());
    }

    @Test
    void sharesNeverExceedTheWholeSky() {
        AbstractContract contract = contract(ContractMoraleLevel.OVERWHELMING,
              spaceScenario(ScenarioStatus.DECISIVE_VICTORY));
        OrbitalControl control = OrbitalControlCalculator.forContract(contract);

        assertTrue(control.playerChance() + control.enemyChance() <= 100,
              "player %d%% and enemy %d%% together claim more than the whole sky".formatted(control.playerChance(),
                    control.enemyChance()));
    }

    @Test
    void aSkyBelongingEntirelyToThePlayerIsAlwaysRolledTheirWay() {
        assertEquals(OrbitalHolder.PLAYER, OrbitalControlCalculator.roll(new OrbitalControl(100, 0)));
    }

    @Test
    void aSkyBelongingEntirelyToTheEnemyIsAlwaysRolledTheirWay() {
        assertEquals(OrbitalHolder.OPFOR, OrbitalControlCalculator.roll(new OrbitalControl(0, 100)));
    }

    @Test
    void anEmptySkyBelongsToNeitherSide() {
        assertEquals(OrbitalHolder.NEITHER, OrbitalControlCalculator.roll(new OrbitalControl(0, 0)));
    }

    @Test
    void aContestedSkyEventuallyFallsToBothSidesAndNeither() {
        boolean player = false;
        boolean opfor = false;
        boolean neither = false;

        // 40/40/20 over a thousand rolls: a run that misses any of the three is a bug, not bad luck.
        for (int i = 0; i < 1000; i++) {
            switch (OrbitalControlCalculator.roll(new OrbitalControl(40, 40))) {
                case PLAYER -> player = true;
                case OPFOR -> opfor = true;
                case NEITHER -> neither = true;
            }
        }

        assertTrue(player && opfor && neither, "a 40/40/20 sky never fell to one of its three outcomes");
    }

    private static Scenario spaceScenario(ScenarioStatus status) {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getBoardType()).thenReturn(Scenario.T_SPACE);
        when(scenario.getStatus()).thenReturn(status);
        return scenario;
    }

    private static AbstractContract contract(ContractMoraleLevel moraleLevel, Scenario... scenarios) {
        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getMoraleLevel()).thenReturn(moraleLevel);
        when(contract.getScenarios()).thenReturn(List.of(scenarios));
        return contract;
    }
}
