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
package mekhq.campaign.digitalGM.stratCon;

import static megamek.common.compute.Compute.randomInt;

import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import megamek.logging.MMLogger;
import mekhq.campaign.mission.scenarios.AtBDynamicScenario;

/**
 * Decides whether a force that rushes into an engagement gets ambushed mid-battle. The decision is made when the force
 * is committed, the only time StratCon still knows whether the hex had been scouted, and is kept on the backing
 * scenario as the round the ambush springs in. The players never see it; the host brings the ambushers in when that
 * round comes.
 * <p>
 * The chance follows how much of the ground was left unscouted: 25% if the hex itself was, plus 5% for each of its
 * neighbours on the map that still is.
 */
public final class StratConAmbushPlanner {
    private static final MMLogger LOGGER = MMLogger.create(StratConAmbushPlanner.class);

    static final int UNSCOUTED_HEX_CHANCE = 25;
    static final int UNSCOUTED_NEIGHBOUR_CHANCE = 5;
    static final int EARLIEST_ROUND = 4;
    static final int LATEST_ROUND = 12;

    private StratConAmbushPlanner() {}

    /**
     * Rolls for a secret ambush on a scenario a force has just been committed to, and records the round it springs
     * in. A scenario that already has an ambush keeps it.
     *
     * @param track         the track the scenario is on
     * @param coords        the hex the force was committed at
     * @param hexWasScouted whether the hex had been scouted before the force went in
     * @param scenario      the scenario the force was committed to
     */
    public static void considerAmbush(StratConTrackState track, StratConCoords coords, boolean hexWasScouted,
          StratConScenario scenario) {
        if ((scenario == null) || (scenario.getBackingScenario() == null)) {
            return;
        }

        AtBDynamicScenario backingScenario = scenario.getBackingScenario();
        if (backingScenario.getSecretAmbushRound() > 0) {
            return;
        }

        boolean trackRevealed = track.isGmRevealed() || track.hasActiveTrackReveal();
        int chance = trackRevealed ?
                           0 :
                           ambushChance(hexWasScouted, neighbours(track, coords) - scoutedNeighbours(track, coords));

        int round = ambushRound(chance, randomInt(100), randomInt(LATEST_ROUND - EARLIEST_ROUND + 1));
        if (round > 0) {
            backingScenario.setSecretAmbushRound(round);
            LOGGER.info("Secret ambush planned for scenario {} in round {} ({}% chance)",
                  backingScenario.getId(), round, chance);
        }
    }

    /**
     * The percentage chance of an ambush.
     *
     * @param hexWasScouted       whether the hex had been scouted before the force went in
     * @param unscoutedNeighbours how many of the hex's neighbours on the map are unscouted
     *
     * @return the chance, from 0 to 55
     */
    static int ambushChance(boolean hexWasScouted, int unscoutedNeighbours) {
        return (hexWasScouted ? 0 : UNSCOUTED_HEX_CHANCE) + (UNSCOUTED_NEIGHBOUR_CHANCE * unscoutedNeighbours);
    }

    /**
     * Turns the rolls into the round an ambush springs in.
     *
     * @param chance     the percentage chance of an ambush
     * @param chanceRoll a roll from 0 to 99
     * @param roundRoll  a roll from 0 to {@code LATEST_ROUND - EARLIEST_ROUND}
     *
     * @return the round the ambush springs in, or 0 for no ambush
     */
    static int ambushRound(int chance, int chanceRoll, int roundRoll) {
        if (chanceRoll >= chance) {
            return 0;
        }
        return EARLIEST_ROUND + roundRoll;
    }

    static int neighbours(StratConTrackState track, StratConCoords coords) {
        return (int) adjacent(track, coords).count();
    }

    static int scoutedNeighbours(StratConTrackState track, StratConCoords coords) {
        Set<StratConCoords> revealed = track.getRevealedCoords();
        return (int) adjacent(track, coords).filter(revealed::contains).count();
    }

    private static Stream<StratConCoords> adjacent(StratConTrackState track, StratConCoords coords) {
        return IntStream.range(0, 6)
                     .mapToObj(coords::translate)
                     .filter(neighbour -> (neighbour.getX() >= 0) && (neighbour.getX() < track.getWidth())
                                                && (neighbour.getY() >= 0) && (neighbour.getY() < track.getHeight()));
    }

}
