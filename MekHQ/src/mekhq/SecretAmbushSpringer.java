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
package mekhq;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import megamek.common.Player;
import megamek.common.annotations.Nullable;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import mekhq.campaign.Campaign;
import mekhq.campaign.mission.scenarios.BotForce;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.SecretAmbush;

/**
 * Springs a scenario's secret ambush from inside the host. The ambush force never enters the lobby, since everything
 * there reaches every player. Instead this listens to the server's own game and, when the Initiative Report phase of
 * the ambush round begins, hands copies of the ambushers to the enemy bot to deploy that round. Nothing about them
 * reaches a player before then.
 * <p>
 * If the enemy has no units left by then, the ambush is called off. If a saved game is loaded mid-session, the
 * springer follows the battle into it.
 */
public class SecretAmbushSpringer extends GameListenerAdapter {
    private static final MMLogger LOGGER = MMLogger.create(SecretAmbushSpringer.class);

    private final Campaign campaign;
    private final BotForce ambush;
    private final TWGameManager gameManager;
    private Game game;
    private boolean done;

    private SecretAmbushSpringer(Campaign campaign, BotForce ambush, Game game, TWGameManager gameManager,
          boolean done) {
        this.campaign = campaign;
        this.ambush = ambush;
        this.game = game;
        this.gameManager = gameManager;
        this.done = done;
    }

    /**
     * Sets the ambush of a scenario about to be played to spring in the server's game, if the scenario has one. Call
     * it once the server has its game, including after loading a saved one.
     *
     * @param server   the server hosting the battle
     * @param campaign the campaign
     * @param scenario the scenario being played
     *
     * @return the springer, or {@code null} if there is no ambush to spring
     */
    public static @Nullable SecretAmbushSpringer attach(Server server, Campaign campaign, Scenario scenario) {
        BotForce ambush = SecretAmbush.find(scenario);
        if ((ambush == null) || !(server.getGame() instanceof Game game)
                  || !(server.getGameManager() instanceof TWGameManager gameManager)) {
            return null;
        }

        boolean alreadySprung = alreadySprung(game, ambush, campaign);
        SecretAmbushSpringer springer = new SecretAmbushSpringer(campaign, ambush, game, gameManager, alreadySprung);
        game.addGameListener(springer);
        if (gameManager instanceof CampaignGameManager campaignGameManager) {
            campaignGameManager.addGameReplacedListener(springer::follow);
        }
        return springer;
    }

    /**
     * Moves to a game the server has swapped in, such as a save a player loaded. Whether the ambush is still to come
     * is judged afresh from that game.
     */
    void follow(Game newGame) {
        game.removeGameListener(this);
        game = newGame;
        done = alreadySprung(newGame, ambush, campaign);
        newGame.addGameListener(this);
    }

    /**
     * @param phase       the phase just begun
     * @param round       the current round
     * @param ambushRound the round the ambush springs in
     * @param done        whether the ambush has already sprung or been called off
     *
     * @return whether the ambush springs now
     */
    static boolean shouldSpring(GamePhase phase, int round, int ambushRound, boolean done) {
        return !done && phase.isInitiativeReport() && (round >= ambushRound);
    }

    /**
     * @return the bot on the ambush's team that still has units in the battle, or {@code null} if the enemy is gone
     */
    static @Nullable Player ambushOwner(Game game, int team) {
        for (Player player : game.getPlayersList()) {
            if (player.isBot() && (player.getTeam() == team) && (game.getLiveDeployedEntitiesOwnedBy(player) > 0)) {
                return player;
            }
        }
        return null;
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent event) {
        if (shouldSpring(event.getNewPhase(), game.getCurrentRound(), ambush.getDeployRound(), done)) {
            spring();
        }
    }

    private void spring() {
        done = true;
        Player owner = ambushOwner(game, ambush.getTeam());
        if (owner == null) {
            LOGGER.info("Secret ambush called off: the enemy has no units left in round {}", game.getCurrentRound());
            return;
        }

        List<Entity> ambushers = new ArrayList<>();
        for (Entity entity : ambush.getFullEntityList(campaign)) {
            Entity copy = copy(entity);
            if (copy != null) {
                ambushers.add(copy);
            }
        }

        gameManager.addSecretReinforcements(owner, ambushers, game.getCurrentRound(), ambush.getStartingPos());
        LOGGER.info("Secret ambush sprung in round {}: {} units join {}",
              game.getCurrentRound(), ambushers.size(), owner.getName());
    }

    private static boolean alreadySprung(Game game, BotForce ambush, Campaign campaign) {
        List<String> ambushIds = new ArrayList<>();
        for (Entity entity : ambush.getFullEntityList(campaign)) {
            if (!"-1".equals(entity.getExternalIdAsString())) {
                ambushIds.add(entity.getExternalIdAsString());
            }
        }
        for (Entity entity : game.getEntitiesVector()) {
            if (ambushIds.contains(entity.getExternalIdAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The server and the campaign share this process, so the game gets its own copy of each unit, made the way the
     * network would copy it, and damage in battle never lands on the campaign's records.
     */
    private static @Nullable Entity copy(Entity entity) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(entity);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (Entity) in.readObject();
            }
        } catch (IOException | ClassNotFoundException ex) {
            LOGGER.error(ex, "Could not copy ambusher {}", entity.getDisplayName());
            return null;
        }
    }
}
