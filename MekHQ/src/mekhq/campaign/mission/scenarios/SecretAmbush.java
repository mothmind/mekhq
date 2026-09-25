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
 */
package mekhq.campaign.mission.scenarios;

import static megamek.common.compute.Compute.randomInt;

import java.util.List;
import java.util.UUID;

import megamek.common.board.Board;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.logging.MMLogger;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.CombatTeam;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.scenarios.ScenarioForceTemplate.ForceAlignment;
import mekhq.campaign.mission.scenarios.ScenarioForceTemplate.ForceGenerationMethod;
import mekhq.campaign.mission.scenarios.ScenarioForceTemplate.SynchronizedDeploymentType;

/**
 * Builds the enemy force for a secret ambush when its battle is launched. StratCon decides at deployment that a
 * scenario gets one ({@link AtBDynamicScenario#getSecretAmbushRound()}); the force itself is only generated as the
 * battle starts, so it never appears in the briefing. It is kept out of the game at setup and brought in by the host
 * in the ambush round, from the rear or a flank of the players' deployment edge.
 */
public final class SecretAmbush {
    private static final MMLogger LOGGER = MMLogger.create(SecretAmbush.class);

    static final String FORCE_NAME = "Ambushers";

    private SecretAmbush() {}

    /**
     * Generates the ambush force for a scenario that has a secret ambush planned, unless it has one already. Only
     * ground battles are ambushed.
     *
     * @param campaign the campaign
     * @param scenario the scenario being launched
     *
     * @return the ambush force, or {@code null} if the scenario has none
     */
    public static BotForce prepare(Campaign campaign, AtBDynamicScenario scenario) {
        int round = scenario.getSecretAmbushRound();
        if ((round <= 0) || (scenario.getBoardType() != Scenario.T_GROUND)) {
            return null;
        }

        BotForce existing = find(scenario);
        if (existing != null) {
            return existing;
        }

        AbstractContract contract = scenario.getContract(campaign);
        int effectiveBV = AtBDynamicScenarioFactory.calculateEffectiveBV(scenario, campaign, false);
        int effectiveUnitCount = AtBDynamicScenarioFactory.calculateEffectiveUnitCount(scenario, campaign, false);
        int lanceSize = CombatTeam.getStandardFormationSize(contract.getEnemyFaction());
        int zone = ambushEdge(scenario.getStartingPos(), randomInt(3));

        ScenarioForceTemplate template = template(forceMultiplier(lanceSize, effectiveUnitCount), zone, round);
        int numBotsBefore = scenario.getNumBots();
        AtBDynamicScenarioFactory.generateForce(scenario, contract, campaign, effectiveBV, effectiveUnitCount,
              AtBDynamicScenarioFactory.randomForceWeight(), template, true);
        if (scenario.getNumBots() == numBotsBefore) {
            LOGGER.warn("Secret ambush for scenario {} generated no force", scenario.getId());
            return null;
        }

        BotForce ambush = scenario.getBotForce(scenario.getNumBots() - 1);
        ambush.setSecretAmbush(true);
        ambush.setStartingPos(zone);
        ambush.setDeployRound(round);
        for (Entity entity : ambush.getFixedEntityList()) {
            if ("-1".equals(entity.getExternalIdAsString())) {
                entity.setExternalIdAsString(UUID.randomUUID().toString());
            }
        }
        LOGGER.info("Secret ambush for scenario {} ready: {} units arriving in round {}",
              scenario.getId(), ambush.getFixedEntityList().size(), round);
        return ambush;
    }

    /**
     * @return the scenario's secret ambush force, or {@code null} if it has none yet
     */
    public static BotForce find(Scenario scenario) {
        for (int i = 0; i < scenario.getNumBots(); i++) {
            if (scenario.getBotForce(i).isSecretAmbush()) {
                return scenario.getBotForce(i);
            }
        }
        return null;
    }

    /**
     * The share of the players' strength the ambush is budgeted at: about one enemy formation's worth, so a lance
     * sprung on a company is a third of its BV, and on a lone lance, a match for it.
     *
     * @param lanceSize          the size of the enemy's standard formation
     * @param effectiveUnitCount the number of player units in the battle
     *
     * @return the BV multiplier, never above 1
     */
    static double forceMultiplier(int lanceSize, int effectiveUnitCount) {
        if (effectiveUnitCount <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) lanceSize / effectiveUnitCount);
    }

    /**
     * The deployment zone the ambush comes from: the players' own edge behind them, or one of the two edges on their
     * flanks.
     *
     * @param playerZone the players' deployment zone, one of the {@code Board.START_} constants
     * @param roll       0 for the rear, 1 or 2 for a flank
     *
     * @return the ambush's deployment zone; any edge if the players' zone is not a single edge
     */
    static int ambushEdge(int playerZone, int roll) {
        if ((playerZone < Board.START_NW) || (playerZone > Board.START_W)) {
            return Board.START_EDGE;
        }
        return switch (roll) {
            case 0 -> playerZone;
            case 1 -> rotate(playerZone, 2);
            default -> rotate(playerZone, -2);
        };
    }

    private static int rotate(int zone, int steps) {
        return Math.floorMod(zone - Board.START_NW + steps, 8) + Board.START_NW;
    }

    private static ScenarioForceTemplate template(double forceMultiplier, int zone, int round) {
        ScenarioForceTemplate template = new ScenarioForceTemplate();
        template.setForceName(FORCE_NAME);
        template.setForceAlignment(ForceAlignment.Opposing.ordinal());
        template.setGenerationMethod(ForceGenerationMethod.BVScaled.ordinal());
        template.setForceMultiplier(forceMultiplier);
        template.setAllowedUnitType(ScenarioForceTemplate.SPECIAL_UNIT_TYPE_ATB_MIX);
        template.setDeploymentZones(List.of(zone));
        template.setActualDeploymentZone(zone);
        template.setSyncDeploymentType(SynchronizedDeploymentType.None);
        template.setDestinationZone(ScenarioForceTemplate.DESTINATION_EDGE_OPPOSITE_DEPLOYMENT);
        template.setArrivalTurn(round);
        template.setMinWeightClass(EntityWeightClass.WEIGHT_ULTRA_LIGHT);
        template.setMaxWeightClass(EntityWeightClass.WEIGHT_ASSAULT);
        template.setContributesToBV(false);
        template.setContributesToUnitCount(false);
        template.setSubjectToRandomRemoval(false);
        return template;
    }
}
