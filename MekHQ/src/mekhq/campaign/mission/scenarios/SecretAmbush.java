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
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.logging.MMLogger;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.CombatTeam;
import mekhq.campaign.force.Formation;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.unit.Unit;
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

    /** Veteran line skill, 3/4. */
    static final int VETERAN_GUNNERY = 3;
    static final int VETERAN_PILOTING = 4;
    /** Regular line skill, 4/5. */
    static final int REGULAR_GUNNERY = 4;
    static final int REGULAR_PILOTING = 5;

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
        int weightClass = playerWeightClass(scenario, campaign);
        int zone = ambushEdge(scenario.getStartingPos(), randomInt(3));

        ScenarioForceTemplate template = template(lanceSize, weightClass, zone, round);
        int numBotsBefore = scenario.getNumBots();
        AtBDynamicScenarioFactory.generateForce(scenario, contract, campaign, effectiveBV, effectiveUnitCount,
              weightClass, template, true);
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
            normaliseCrew(entity);
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
     * Rerolls an ambusher's crew to ordinary line skill. The ambush is a fixed lance rather than a battle-value
     * match, so nothing else keeps it in proportion: without this the generator hands the ambushers whatever skill
     * the contract's opposing force uses, which against an elite company is a second elite company.
     *
     * <p>Each pilot is rolled independently, so a lance comes out mixed rather than uniformly good or bad.</p>
     *
     * @param entity the ambusher to reskill
     */
    static void normaliseCrew(Entity entity) {
        if ((entity == null) || (entity.getCrew() == null)) {
            return;
        }
        Crew crew = entity.getCrew();
        for (int slot = 0; slot < crew.getSlotCount(); slot++) {
            boolean veteran = randomInt(2) == 0;
            crew.setGunnery(veteran ? VETERAN_GUNNERY : REGULAR_GUNNERY, slot);
            crew.setPiloting(veteran ? VETERAN_PILOTING : REGULAR_PILOTING, slot);
        }
    }

    /**
     * The weight class the ambush is built at: the average of the player units committed to the scenario, so the
     * ambushers roughly mirror what they are jumping.
     *
     * @param scenario the scenario the force was committed to
     * @param campaign the campaign, for looking up the player's units
     *
     * @return an {@link EntityWeightClass} constant, defaulting to medium when nothing can be read
     */
    static int playerWeightClass(AtBDynamicScenario scenario, Campaign campaign) {
        int total = 0;
        int counted = 0;

        for (int forceId : scenario.getForceIDs()) {
            Formation formation = campaign.getPlayerForce().getFormation(forceId);
            if (formation == null) {
                continue;
            }
            for (UUID unitId : formation.getAllUnits(false)) {
                Unit unit = campaign.getUnit(unitId);
                if ((unit != null) && (unit.getEntity() != null)) {
                    total += unit.getEntity().getWeightClass();
                    counted++;
                }
            }
        }
        for (UUID unitId : scenario.getIndividualUnitIDs()) {
            Unit unit = campaign.getUnit(unitId);
            if ((unit != null) && (unit.getEntity() != null)) {
                total += unit.getEntity().getWeightClass();
                counted++;
            }
        }

        return (counted == 0) ? EntityWeightClass.WEIGHT_MEDIUM : averageWeightClass(total, counted);
    }

    /**
     * @param totalWeightClass the summed weight classes of the player's units
     * @param unitCount        how many units were summed
     *
     * @return the average, rounded to nearest and clamped to the light-to-assault band
     */
    static int averageWeightClass(int totalWeightClass, int unitCount) {
        if (unitCount <= 0) {
            return EntityWeightClass.WEIGHT_MEDIUM;
        }
        int average = (int) Math.round((double) totalWeightClass / unitCount);
        return Math.clamp(average, EntityWeightClass.WEIGHT_LIGHT, EntityWeightClass.WEIGHT_ASSAULT);
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

    private static ScenarioForceTemplate template(int lanceSize, int weightClass, int zone, int round) {
        ScenarioForceTemplate template = new ScenarioForceTemplate();
        template.setForceName(FORCE_NAME);
        template.setForceAlignment(ForceAlignment.Opposing.ordinal());
        template.setGenerationMethod(ForceGenerationMethod.FixedUnitCount.ordinal());
        template.setFixedUnitCount(lanceSize);
        template.setAllowedUnitType(ScenarioForceTemplate.SPECIAL_UNIT_TYPE_ATB_MIX);
        template.setDeploymentZones(List.of(zone));
        template.setActualDeploymentZone(zone);
        template.setSyncDeploymentType(SynchronizedDeploymentType.None);
        template.setDestinationZone(ScenarioForceTemplate.DESTINATION_EDGE_OPPOSITE_DEPLOYMENT);
        template.setArrivalTurn(round);
        template.setMinWeightClass(weightClass);
        template.setMaxWeightClass(weightClass);
        template.setContributesToBV(false);
        template.setContributesToUnitCount(false);
        template.setSubjectToRandomRemoval(false);
        return template;
    }
}
