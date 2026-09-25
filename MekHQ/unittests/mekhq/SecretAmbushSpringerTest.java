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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.server.Server;
import mekhq.campaign.Campaign;
import mekhq.campaign.mission.scenarios.BotForce;
import mekhq.campaign.mission.scenarios.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for springing a secret ambush inside the host: a real server and game, with the springer listening as the
 * phases go by.
 */
class SecretAmbushSpringerTest {

    private static final int AMBUSH_ROUND = 6;

    private Server server;
    private CampaignGameManager gameManager;
    private Game game;
    private Player bot;
    private Entity enemyOnTheField;
    private Entity ambusher;
    private Scenario scenario;
    private final Campaign campaign = mock(Campaign.class);

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() throws IOException {
        gameManager = new CampaignGameManager();
        server = new Server(null, freePort(), gameManager, false, "", null, true);
        game = gameManager.getGame();

        Player human = new Player(0, "Players");
        human.setTeam(1);
        bot = new Player(1, "OpFor");
        bot.setTeam(2);
        bot.setBot(true);
        game.addPlayer(0, human);
        game.addPlayer(1, bot);

        enemyOnTheField = mek("Defender");
        enemyOnTheField.setOwner(bot);
        game.addEntity(enemyOnTheField);

        ambusher = mek("Ambusher");
        ambusher.setExternalIdAsString(UUID.randomUUID().toString());
        BotForce ambush = new BotForce("Ambushers", 2, Board.START_S, new ArrayList<>(List.of(ambusher)));
        ambush.setSecretAmbush(true);
        ambush.setDeployRound(AMBUSH_ROUND);
        scenario = new Scenario();
        scenario.addBotForce(ambush, campaign);
    }

    @AfterEach
    void tearDown() {
        server.die();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Entity mek(String model) {
        BipedMek mek = new BipedMek();
        mek.setChassis("Test");
        mek.setModel(model);
        mek.setWeight(50.0);
        mek.setOriginalWalkMP(5);
        mek.autoSetInternal();
        mek.setCrew(new Crew(CrewType.SINGLE));
        return mek;
    }

    private void enterPhase(int round, GamePhase phase) {
        game.setCurrentRound(round);
        game.setPhase(phase);
    }

    private List<Entity> ambushersInGame() {
        return game.getEntitiesVector().stream()
                     .filter(entity -> entity.getExternalIdAsString().equals(ambusher.getExternalIdAsString()))
                     .toList();
    }

    @Test
    @DisplayName("the ambush waits for the Initiative Report phase of its round, then joins the enemy bot")
    void springsInItsRound() {
        assertNotNull(SecretAmbushSpringer.attach(server, campaign, scenario));

        enterPhase(AMBUSH_ROUND - 1, GamePhase.INITIATIVE_REPORT);
        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE);
        assertTrue(ambushersInGame().isEmpty(), "nothing enters the game before the ambush round's report phase");

        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE_REPORT);

        List<Entity> sprung = ambushersInGame();
        assertEquals(1, sprung.size());
        Entity inGame = sprung.getFirst();
        assertNotSame(ambusher, inGame, "the game gets its own copy, so battle damage never reaches the campaign");
        assertEquals(bot.getId(), inGame.getOwnerId());
        assertEquals(AMBUSH_ROUND, inGame.getDeployRound());
        assertEquals(Board.START_S, inGame.getStartingPos());
        assertFalse(inGame.isDeployed());
    }

    @Test
    @DisplayName("the ambush springs only once")
    void springsOnce() {
        SecretAmbushSpringer.attach(server, campaign, scenario);

        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE_REPORT);
        enterPhase(AMBUSH_ROUND + 1, GamePhase.INITIATIVE);
        enterPhase(AMBUSH_ROUND + 1, GamePhase.INITIATIVE_REPORT);

        assertEquals(1, ambushersInGame().size());
    }

    @Test
    @DisplayName("the ambush is called off when the enemy has no units left")
    void calledOffWhenTheEnemyIsGone() {
        SecretAmbushSpringer.attach(server, campaign, scenario);
        enemyOnTheField.setDestroyed(true);

        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE_REPORT);

        assertTrue(ambushersInGame().isEmpty());
    }

    @Test
    @DisplayName("a reloaded game in which the ambush already sprang does not get it twice")
    void reloadedGameIsNotAmbushedTwice() {
        Entity alreadyThere = mek("Ambusher");
        alreadyThere.setExternalIdAsString(ambusher.getExternalIdAsString());
        alreadyThere.setOwner(bot);
        game.addEntity(alreadyThere);

        SecretAmbushSpringer.attach(server, campaign, scenario);
        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE_REPORT);

        assertEquals(1, ambushersInGame().size());
    }

    @Test
    @DisplayName("when a player loads a saved game mid-session, the ambush follows the battle into it")
    void followsALoadedGame() {
        SecretAmbushSpringer.attach(server, campaign, scenario);
        Game oldGame = game;

        Game loaded = new Game();
        Player loadedBot = new Player(1, "OpFor");
        loadedBot.setTeam(2);
        loadedBot.setBot(true);
        loaded.addPlayer(0, new Player(0, "Players"));
        loaded.addPlayer(1, loadedBot);
        Entity loadedDefender = mek("Defender");
        loadedDefender.setOwner(loadedBot);
        loaded.addEntity(loadedDefender);
        gameManager.setGame(loaded);
        game = loaded;

        enterPhase(AMBUSH_ROUND, GamePhase.INITIATIVE_REPORT);

        assertEquals(1, ambushersInGame().size(), "the ambush springs in the loaded game");
        assertTrue(oldGame.getEntitiesVector().stream()
                         .noneMatch(entity -> entity.getExternalIdAsString()
                                                    .equals(ambusher.getExternalIdAsString())),
              "and not in the game that was replaced");
    }

    @Test
    @DisplayName("a scenario without a secret ambush attaches nothing")
    void noAmbushNoSpringer() {
        assertNull(SecretAmbushSpringer.attach(server, campaign, new Scenario()));
    }
}
