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
package mekhq.pilotChatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.Executor;

import megamek.common.InfernoTracker;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.event.GameSurrenderEvent;
import megamek.common.event.GameWithdrawalEvent;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.PlayerForce;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.unit.Unit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for pilot chatter in a running game: phases go by, pilots react, their lines are journaled and delivered.
 */
class PilotChatterTest {

    @TempDir
    Path tempDir;

    private Game game;
    private Player players;
    private Player opFor;
    private PilotJournal journal;
    private final List<String> prompts = new ArrayList<>();
    private final List<String> delivered = new ArrayList<>();
    private final List<String> popups = new ArrayList<>();
    private final List<List<Integer>> listenerIds = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private final Campaign campaign = mock(Campaign.class);
    private int failuresToThrow;
    private String reply;
    private BattleBriefing briefing = BattleBriefing.NONE;
    private ChatterTuning tuning = ChatterTuning.DEFAULT;
    private int deliveriesToFail;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        when(campaign.getLocalDate()).thenReturn(LocalDate.of(3067, 3, 1));
        game = newGame();
        players = game.getPlayer(0);
        opFor = game.getPlayer(1);
        journal = new PilotJournal(tempDir.resolve("journal.jsonl"));
    }

    private static Game newGame() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        players.setTeam(1);
        Player opFor = new Player(1, "OpFor");
        opFor.setTeam(2);
        game.addPlayer(0, players);
        game.addPlayer(1, opFor);
        return game;
    }

    private static Entity mek(Game game, Player owner, String pilot) {
        BipedMek mek = new BipedMek();
        mek.setChassis("Test");
        mek.setModel(pilot);
        mek.setWeight(50.0);
        mek.autoSetInternal();
        for (int location = 0; location < mek.locations(); location++) {
            mek.initializeArmor(10, location);
        }
        mek.setCrew(new Crew(CrewType.SINGLE));
        mek.getCrew().setName(pilot, 0);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private PilotChatter chatter(int roll) {
        return chatter(roll, Runnable::run);
    }

    private PilotChatter chatter(int roll, Executor executor) {
        PilotChatter.LineSource source = (system, prompt) -> {
            if (failuresToThrow > 0) {
                failuresToThrow--;
                throw new IOException("connection refused");
            }
            prompts.add(prompt);
            return Optional.of((reply == null) ? "Line " + prompts.size() : reply);
        };
        PilotChatter.Delivery delivery = new PilotChatter.Delivery() {
            @Override
            public void deliver(List<Player> listeners, String callName, String line) {
                if (deliveriesToFail > 0) {
                    deliveriesToFail--;
                    throw new IllegalStateException("connection dropped");
                }
                delivered.add(callName + ": " + line);
                listenerIds.add((listeners == null) ? null : listeners.stream().map(Player::getId).toList());
            }

            @Override
            public void showSpeech(List<Player> listeners, int entityId, String callName, String line) {
                popups.add(entityId + " " + callName + ": " + line);
            }

            @Override
            public void notice(String message) {
                notices.add(message);
            }
        };
        PilotChatter chatter = new PilotChatter(campaign, "Battle A", "Draconis Combine", "http://localhost:11434",
              briefing, tuning, journal, source, delivery, executor, bound -> roll);
        chatter.watch(game);
        return chatter;
    }

    private void phase(GamePhase phase) {
        game.setPhase(phase);
    }

    @Test
    @DisplayName("a pilot who takes hits speaks up after the phase, and the line is journaled and delivered")
    void speaksAfterDamage() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);

        hatchet.setArmor(2, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(List.of("Hatchet (Test Hatchet): Line 1"), delivered);
        assertTrue(prompts.getFirst().contains("took hits"));
        assertTrue(prompts.getFirst().contains("They took 8 damage."));
        PilotJournal.Entry entry = journal.recentInBattle("Battle A", 1).getFirst();
        assertEquals("Line 1", entry.line());
        assertEquals("DAMAGED", entry.event());
        assertEquals("3067-03-01", entry.campaignDate());
    }

    @Test
    @DisplayName("a spoken line also pops up on the board, against the unit that said it")
    void popsUpAgainstTheSpeaker() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);

        hatchet.setArmor(2, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(List.of(hatchet.getId() + " Hatchet (Test Hatchet): Line 1"), popups);
        assertEquals(delivered.size(), popups.size(), "every line in chat is on the board as well");
    }

    @Test
    @DisplayName("a kill is always worth a line, and the victim gets last words when the pilot dies")
    void killAndLastWords() {
        Entity killer = mek(game, players, "Hatchet");
        Entity victim = mek(game, opFor, "Kenji");
        chatter(99);
        phase(GamePhase.FIRING);

        killer.addKill(victim);
        victim.setDestroyed(true);
        victim.getCrew().setDoomed(true);
        victim.getCrew().setDead(true);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(2, delivered.size(), "both are big moments, so the high roll does not silence them");
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("destroyed an enemy unit")));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("last words")
                                                               && prompt.contains("enemy pilot, fighting for "
                                                                                        + "Draconis Combine")));
    }

    @Test
    @DisplayName("a pilot MegaMek has only marked as doomed, as it does until the next phase, still gets last words")
    void lastWordsWhileDoomed() {
        Entity victim = mek(game, opFor, "Kenji");
        chatter(99);
        phase(GamePhase.FIRING);

        victim.setDoomed(true);
        victim.getCrew().setDoomed(true);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, delivered.size());
        assertTrue(prompts.getFirst().contains("last words"));
    }

    @Test
    @DisplayName("a pilot killed by a headshot is cut off mid-sentence, in chat and in the journal")
    void headshotCutsOff() {
        Entity victim = mek(game, opFor, "Kenji");
        reply = "Contact left, I've got eyes on the Atlas now.";
        chatter(1);
        phase(GamePhase.FIRING);

        victim.setArmor(0, Mek.LOC_HEAD);
        victim.setDoomed(true);
        victim.getCrew().setDoomed(true);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(List.of("Kenji (Test Kenji): Contact left, I\u2014"), delivered);
        assertTrue(prompts.getFirst().contains("killed instantly by a shot to the head"));
        assertFalse(prompts.getFirst().contains("last words"));
        PilotJournal.Entry entry = journal.recentInBattle("Battle A", 1).getFirst();
        assertEquals("Contact left, I\u2014", entry.line(), "the journal remembers the line as it was heard");
        assertEquals("HEADSHOT", entry.event());
    }

    @Test
    @DisplayName("a pose forces a one-liner")
    void poseForcesALine() {
        Entity poser = mek(game, players, "Showboat");
        chatter(99);
        phase(GamePhase.MOVEMENT);

        poser.setPosing(true);
        phase(GamePhase.MOVEMENT_REPORT);

        assertEquals(1, delivered.size());
        assertTrue(prompts.getFirst().contains("struck a dramatic pose"));
    }

    @Test
    @DisplayName("a pilot's own earlier lines and the battle's chatter go into their next prompt")
    void continuity() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);
        hatchet.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);
        phase(GamePhase.PHYSICAL);
        hatchet.setArmor(4, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.PHYSICAL_REPORT);

        String second = prompts.get(1);
        assertTrue(second.contains("WHAT THIS PILOT HAS SAID BEFORE\n- Line 1"));
        assertTrue(second.contains("RECENT CHATTER IN THIS BATTLE\n- [your side] Hatchet (Test Hatchet): Line 1"));
    }

    @Test
    @DisplayName("the battle reports about a pilot are part of what they react to")
    void reportContext() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);
        Report report = new Report(3390);
        report.subject = hatchet.getId();
        report.add("Hatchet");
        game.addReports(new ArrayList<>(List.of(report)));
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, delivered.size(), "being in the reports during the firing phase is being in action");
        assertTrue(prompts.getFirst().contains("in the thick of it"));
    }

    @Test
    @DisplayName("when the model cannot be reached the pilots fall silent for the battle, and the players are told")
    void silencedWhenUnreachable() {
        Entity hatchet = mek(game, players, "Hatchet");
        PilotChatter chatter = chatter(0);
        failuresToThrow = 100;

        for (int round = 1; round <= 5; round++) {
            phase(GamePhase.FIRING);
            hatchet.setArmor(10 - round, Mek.LOC_CENTER_TORSO);
            phase(GamePhase.FIRING_REPORT);
        }

        assertTrue(chatter.isSilenced());
        assertEquals(1, notices.size());
        assertTrue(notices.getFirst().contains("http://localhost:11434"));
        assertTrue(notices.getFirst().contains("(connection refused)"), "the players are told why: " + notices);
        assertEquals(PilotChatter.MAX_CONSECUTIVE_FAILURES, 100 - failuresToThrow,
              "no more requests once silenced");
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("one pilot's broken record costs only their line: the phase goes on and the others still speak")
    void brokenPilotStaysContained() {
        Entity broken = mek(game, players, "Broken");
        UUID unitId = UUID.randomUUID();
        broken.setExternalIdAsString(unitId.toString());
        Person person = mock(Person.class);
        when(person.getCallsign()).thenThrow(new IllegalStateException("corrupt record"));
        Unit unit = mock(Unit.class);
        when(unit.getCommander()).thenReturn(person);
        when(campaign.getUnit(unitId)).thenReturn(unit);
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0);
        phase(GamePhase.FIRING);

        broken.setArmor(2, Mek.LOC_CENTER_TORSO);
        kenji.setArmor(2, Mek.LOC_CENTER_TORSO);

        assertDoesNotThrow(() -> phase(GamePhase.FIRING_REPORT));
        assertEquals(List.of("Kenji (Test Kenji): Line 1"), delivered);
    }

    @Test
    @DisplayName("a failure inside chatter never reaches MegaMek's phase change, which would stall the battle")
    void failureNeverReachesTheGame() {
        Entity hatchet = mek(game, players, "Hatchet");
        hatchet.setExternalIdAsString(UUID.randomUUID().toString());
        when(campaign.getUnit(any(UUID.class))).thenThrow(new IllegalStateException("campaign busy"));
        chatter(0);
        phase(GamePhase.FIRING);

        hatchet.setArmor(2, Mek.LOC_CENTER_TORSO);

        assertDoesNotThrow(() -> phase(GamePhase.FIRING_REPORT));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("a failure while a line is on its way is logged, not thrown out of the background thread")
    void backgroundFailureContained() {
        List<Throwable> escaped = new ArrayList<>();
        Entity hatchet = mek(game, players, "Hatchet");
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0, task -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                escaped.add(ex);
            }
        });
        deliveriesToFail = 1;
        phase(GamePhase.FIRING);

        hatchet.setArmor(2, Mek.LOC_CENTER_TORSO);
        kenji.setArmor(2, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertTrue(escaped.isEmpty(), "a thrown task would kill the chatter's worker thread");
        assertEquals(List.of("Kenji (Test Kenji): Line 2"), delivered);
    }

    @Test
    @DisplayName("allies and enemies are told apart by the campaign's own side, even when the players are not team 1")
    void sidesFollowTheCampaign() {
        players.setTeam(2);
        opFor.setTeam(1);
        Player allies = new Player(2, "Allies");
        allies.setTeam(2);
        game.addPlayer(2, allies);
        Entity ours = mek(game, players, "Hatchet");
        UUID unitId = UUID.randomUUID();
        ours.setExternalIdAsString(unitId.toString());
        when(campaign.getUnit(unitId)).thenReturn(mock(Unit.class));
        Entity marcus = mek(game, allies, "Marcus");
        chatter(0);
        phase(GamePhase.FIRING);

        marcus.setArmor(2, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertTrue(prompts.getFirst().contains("an allied pilot fighting alongside the player's MekWarriors"));
    }

    private void doubleBlind() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        opFor.setBot(true);
    }

    @Test
    @DisplayName("under double-blind an enemy no human can see says nothing; one in sight is heard by those who see it")
    void unseenEnemyIsSilent() {
        doubleBlind();
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0);
        phase(GamePhase.FIRING);
        kenji.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertTrue(prompts.isEmpty(), "a line nobody would hear is never asked for");

        kenji.setWhoCanSee(new Vector<>(List.of(players)));
        phase(GamePhase.PHYSICAL);
        kenji.setArmor(6, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.PHYSICAL_REPORT);

        assertEquals(List.of("Kenji (Test Kenji): Line 1"), delivered);
        assertEquals(List.of(List.of(0, 1)), listenerIds, "the player who sees Kenji, and Kenji's own side");
    }

    @Test
    @DisplayName("under double-blind the players' pilots are heard only by their own side, even when the enemy sees")
    void ownSideStaysOnOwnSide() {
        doubleBlind();
        Entity hatchet = mek(game, players, "Hatchet");
        hatchet.setWhoCanSee(new Vector<>(List.of(opFor)));
        chatter(0);
        phase(GamePhase.FIRING);
        hatchet.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(List.of(List.of(0)), listenerIds);
    }

    @Test
    @DisplayName("under double-blind a pilot draws only on the chatter their listener actually heard")
    void chatterFollowsWhoHeardIt() {
        doubleBlind();
        Player wingman = new Player(2, "Wingman");
        wingman.setTeam(1);
        game.addPlayer(2, wingman);
        Entity kenji = mek(game, opFor, "Kenji");
        Entity hatchet = mek(game, players, "Hatchet");
        Entity grin = mek(game, wingman, "Grin");
        kenji.setWhoCanSee(new Vector<>(List.of(players)));
        chatter(0);
        phase(GamePhase.FIRING);
        kenji.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        phase(GamePhase.PHYSICAL);
        hatchet.setArmor(8, Mek.LOC_CENTER_TORSO);
        grin.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.PHYSICAL_REPORT);

        String hatchetPrompt = prompts.stream().filter(p -> p.contains("Name: Hatchet")).findFirst().orElseThrow();
        String grinPrompt = prompts.stream().filter(p -> p.contains("Name: Grin")).findFirst().orElseThrow();
        assertTrue(hatchetPrompt.contains("[enemy] Kenji (Test Kenji): Line 1"), hatchetPrompt);
        assertFalse(grinPrompt.contains("Line 1"), "the wingman never saw Kenji, so never heard him: " + grinPrompt);
        assertTrue(grinPrompt.contains("[your side] Hatchet (Test Hatchet): Line 2"), grinPrompt);
    }

    @Test
    @DisplayName("under double-blind an enemy never answers the players' own comms, even when a player can see it")
    void enemyNeverHearsOurComms() {
        doubleBlind();
        Entity hatchet = mek(game, players, "Hatchet");
        Entity kenji = mek(game, opFor, "Kenji");
        kenji.setWhoCanSee(new Vector<>(List.of(players)));
        chatter(0);
        phase(GamePhase.FIRING);
        hatchet.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        phase(GamePhase.PHYSICAL);
        kenji.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.PHYSICAL_REPORT);

        String kenjiPrompt = prompts.stream().filter(p -> p.contains("Name: Kenji")).findFirst().orElseThrow();
        assertFalse(kenjiPrompt.contains("Line 1"), "Hatchet spoke on the players' own channel: " + kenjiPrompt);
    }

    private Entity campaignPilot(String callsign) {
        Entity mek = mek(game, players, callsign);
        UUID unitId = UUID.randomUUID();
        mek.setExternalIdAsString(unitId.toString());
        Person person = mock(Person.class);
        when(person.getId()).thenReturn(UUID.randomUUID());
        when(person.getCallsign()).thenReturn(callsign);
        Unit unit = mock(Unit.class);
        when(unit.getCommander()).thenReturn(person);
        when(campaign.getUnit(unitId)).thenReturn(unit);
        return mek;
    }

    @Test
    @DisplayName("our pilots are briefed on the story, the mission and their comrades; outsiders on the public face")
    void briefings() {
        PlayerForce force = mock(PlayerForce.class);
        when(force.getName()).thenReturn("Remnants");
        when(campaign.getPlayerForce()).thenReturn(force);
        briefing = new BattleBriefing(new CompanyLore("The last of ComStar's Explorer Corps.",
              "The Remnants, a mercenary company fresh off a long guerrilla war."), "Remnants",
              "Contract: Shaunavon Guerrilla Warfare, for the Taurian Concordat against the Federated Suns.");
        Entity bomber = campaignPilot("Bomber");
        Entity comfort = campaignPilot("Comfort");
        Entity kenji = mek(game, opFor, "Kenji");
        comfort.setDestroyed(true);
        chatter(0);
        phase(GamePhase.FIRING);

        bomber.setArmor(8, Mek.LOC_CENTER_TORSO);
        kenji.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        String bomberPrompt = prompts.stream().filter(p -> p.contains("callsign \"Bomber\"")).findFirst().orElseThrow();
        String kenjiPrompt = prompts.stream().filter(p -> p.contains("Name: Kenji")).findFirst().orElseThrow();
        assertTrue(bomberPrompt.contains("THE COMPANY\nThe last of ComStar's Explorer Corps."), bomberPrompt);
        assertTrue(bomberPrompt.contains("THE MISSION\nContract: Shaunavon Guerrilla Warfare"), bomberPrompt);
        assertTrue(bomberPrompt.contains("THE PILOT'S COMRADES IN THIS BATTLE\n"
                                               + "- Comfort (Test Comfort), out of the fight"), bomberPrompt);
        assertTrue(kenjiPrompt.contains("WHO THEY ARE FIGHTING\nThe Remnants, a mercenary company"), kenjiPrompt);
        assertFalse(kenjiPrompt.contains("ComStar"), "the enemy knows only the cover story");
        assertFalse(kenjiPrompt.contains("THE MISSION"), "nor the players' orders");
        int recall = bomberPrompt.indexOf("- Something about this moment takes the pilot back to where the company "
                                                + "comes from");
        assertTrue(recall > bomberPrompt.indexOf("WHAT JUST HAPPENED"),
              "a low roll asks for the story: " + bomberPrompt);
        assertFalse(kenjiPrompt.contains("takes the pilot back"), "outsiders have no story to recall");
    }

    @Test
    @DisplayName("most lines leave the company's story in the background")
    void storyStaysInTheBackground() {
        PlayerForce force = mock(PlayerForce.class);
        when(force.getName()).thenReturn("Remnants");
        when(campaign.getPlayerForce()).thenReturn(force);
        briefing = new BattleBriefing(new CompanyLore("The last of ComStar's Explorer Corps.", ""), "Remnants", "");
        Entity bomber = campaignPilot("Bomber");
        chatter(ChatterTuning.DEFAULT_LORE_CHANCE);
        phase(GamePhase.FIRING);

        bomber.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertTrue(prompts.getFirst().contains("The last of ComStar's Explorer Corps."));
        assertFalse(prompts.getFirst().contains("takes the pilot back"));
    }

    @Test
    @DisplayName("with enemy chatter off, enemy pilots stay silent even at their big moments; ours still speak")
    void enemyChatterOff() {
        tuning = new ChatterTuning(Chattiness.CHATTY, false, 0);
        Entity hatchet = mek(game, players, "Hatchet");
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0);
        phase(GamePhase.FIRING);

        hatchet.addKill(kenji);
        kenji.setDestroyed(true);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(List.of("Hatchet (Test Hatchet): Line 1"), delivered);
    }

    @Test
    @DisplayName("on quiet only the big moments speak, and a lore chance of 0 never asks for the story")
    void quietAndNoLore() {
        tuning = new ChatterTuning(Chattiness.QUIET, true, 0);
        PlayerForce force = mock(PlayerForce.class);
        when(force.getName()).thenReturn("Remnants");
        when(campaign.getPlayerForce()).thenReturn(force);
        briefing = new BattleBriefing(new CompanyLore("The last of ComStar's Explorer Corps.", ""), "Remnants", "");
        Entity bomber = campaignPilot("Bomber");
        Entity grin = mek(game, players, "Grin");
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0);
        phase(GamePhase.FIRING);

        grin.setArmor(2, Mek.LOC_CENTER_TORSO);
        bomber.addKill(kenji);
        kenji.setDestroyed(true);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(2, delivered.size(), "the kill and the destruction, not Grin's damage: " + delivered);
        assertTrue(prompts.stream().noneMatch(prompt -> prompt.contains("takes the pilot back")));
    }

    @Test
    @DisplayName("when a saved game is loaded mid-session the pilots follow the battle into it")
    void followsALoadedGame() {
        PilotChatter chatter = chatter(0);
        Game loaded = newGame();
        Entity hatchet = mek(loaded, loaded.getPlayer(0), "Hatchet");
        Game old = game;

        chatter.follow(loaded);
        game = loaded;
        phase(GamePhase.FIRING);
        hatchet.setArmor(1, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, delivered.size());
        old.setPhase(GamePhase.FIRING_REPORT);
        assertEquals(1, delivered.size(), "the replaced game is no longer listened to");
    }

    private void surrenders(Player player, boolean admitted) {
        game.processGameEvent(new GameSurrenderEvent(this, player.getId(), admitted));
    }

    @Test
    @DisplayName("when the enemy surrenders, their commander declares it, heard by everyone even under double-blind")
    void commanderDeclaresTheSurrender() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        mek(game, players, "Hatchet");
        mek(game, opFor, "Kenji");
        Entity commander = mek(game, opFor, "Tai-sa Ito");
        commander.setCommander(true);
        chatter(99);

        surrenders(opFor, false);

        assertEquals(List.of("Tai-sa Ito (Test Tai-sa Ito): Line 1"), delivered);
        assertEquals(List.of(commander.getId() + " Tai-sa Ito (Test Tai-sa Ito): Line 1"), popups);
        assertTrue(prompts.getFirst().contains("surrendering it to the enemy over open comms"));
        assertTrue(prompts.getFirst().contains("They are the force's commander."));
        assertEquals(1, listenerIds.size());
        assertEquals(null, listenerIds.getFirst(), "a surrender goes out to every player, not just those who see it");
        assertEquals("SURRENDER", journal.recentInBattle("Battle A", 1).getFirst().event());
    }

    @Test
    @DisplayName("with the commander dead, the surrender falls to whoever is left in charge")
    void surrenderFallsToWhoeverIsLeft() {
        Entity commander = mek(game, opFor, "Tai-sa Ito");
        commander.setCommander(true);
        commander.getCrew().setDead(true);
        Entity green = mek(game, opFor, "Green");
        green.getCrew().setGunnery(5, 0);
        green.getCrew().setPiloting(6, 0);
        Entity veteran = mek(game, opFor, "Kenji");
        veteran.getCrew().setGunnery(2, 0);
        veteran.getCrew().setPiloting(3, 0);
        chatter(99);

        surrenders(opFor, false);

        assertEquals(List.of("Kenji (Test Kenji): Line 1"), delivered, "the better pilot outranks the green one");
        assertTrue(prompts.getFirst().contains("the surrender falls to them"));
    }

    @Test
    @DisplayName("the force's command bonus, as initiative counts it, outranks raw piloting skill")
    void commandBonusOutranksSkill() {
        game.getOptions().getOption(OptionsConstants.RPG_COMMAND_INIT).setValue(true);
        Entity leader = mek(game, opFor, "Leader");
        leader.setDeployed(true);
        leader.getCrew().setGunnery(5, 0);
        leader.getCrew().setPiloting(6, 0);
        leader.getCrew().setCommandBonus(2);
        Entity ace = mek(game, opFor, "Ace");
        ace.setDeployed(true);
        ace.getCrew().setGunnery(2, 0);
        ace.getCrew().setPiloting(3, 0);

        assertEquals(leader, PilotChatter.surrenderSpeaker(game, opFor));
    }

    @Test
    @DisplayName("a side surrenders once: repeated offers and the final acknowledgment say nothing more")
    void surrenderIsDeclaredOnce() {
        mek(game, opFor, "Kenji");
        chatter(99);

        surrenders(opFor, false);
        surrenders(opFor, false);
        surrenders(opFor, true);

        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("only the enemy's surrender is voiced, never the players' own")
    void onlyEnemySurrendersAreVoiced() {
        mek(game, players, "Hatchet");
        chatter(99);

        surrenders(players, false);

        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("with enemy chatter switched off, the enemy surrenders in silence")
    void enemyChatterOffSilencesTheSurrender() {
        mek(game, opFor, "Kenji");
        tuning = new ChatterTuning(Chattiness.CHATTY, false, ChatterTuning.DEFAULT_LORE_CHANCE);
        chatter(99);

        surrenders(opFor, false);

        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("a force with nobody left who could speak surrenders in silence")
    void nobodyLeftToSpeak() {
        Entity wreck = mek(game, opFor, "Kenji");
        wreck.setDestroyed(true);
        chatter(99);

        assertDoesNotThrow(() -> surrenders(opFor, false));
        assertTrue(delivered.isEmpty());
    }

    private void reports(Entity unit, boolean returningFire) {
        game.processGameEvent(new GameWithdrawalEvent(this, unit.getId(), returningFire));
    }

    @Test
    @DisplayName("a bot unit that starts withdrawing always says so, on the board against its icon")
    void withdrawingPilotSaysSo() {
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(99);

        reports(kenji, false);

        assertEquals(List.of("Kenji (Test Kenji): Line 1"), delivered);
        assertEquals(List.of(kenji.getId() + " Kenji (Test Kenji): Line 1"), popups);
        assertTrue(prompts.getFirst().contains("pulling out of the fight and withdrawing"));
        assertEquals("WITHDRAWING", journal.recentInBattle("Battle A", 1).getFirst().event());
    }

    @Test
    @DisplayName("a withdrawing unit that turns to shoot back says that too")
    void returningFirePilotSaysSo() {
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(99);

        reports(kenji, false);
        reports(kenji, true);

        assertEquals(2, delivered.size());
        assertTrue(prompts.get(1).contains("will now shoot back as they keep pulling out"));
        assertEquals("RETURNING_FIRE", journal.recentInBattle("Battle A", 1).getFirst().event());
    }

    @Test
    @DisplayName("each change of course is said once per unit, however often it is reported")
    void eachCourseIsSaidOnce() {
        Entity kenji = mek(game, opFor, "Kenji");
        Entity hiro = mek(game, opFor, "Hiro");
        chatter(99);

        reports(kenji, false);
        reports(kenji, false);
        reports(hiro, false);

        assertEquals(2, delivered.size(), "Kenji once, Hiro once");
    }

    @Test
    @DisplayName("with enemy chatter switched off, an enemy pulls out in silence")
    void enemyChatterOffSilencesWithdrawal() {
        Entity kenji = mek(game, opFor, "Kenji");
        tuning = new ChatterTuning(Chattiness.CHATTY, false, ChatterTuning.DEFAULT_LORE_CHANCE);
        chatter(99);

        reports(kenji, false);

        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("once a unit is withdrawing, everything its pilot says is terrified")
    void withdrawingPilotStaysTerrified() {
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(0);
        reports(kenji, false);
        phase(GamePhase.FIRING);

        kenji.setArmor(2, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(2, prompts.size(), "the announcement, then the damage line");
        assertTrue(prompts.get(0).contains(ChatterTone.TERRIFIED));
        assertTrue(prompts.get(1).contains(ChatterTone.TERRIFIED), "the later damage line must stay terrified");
    }

    @Test
    @DisplayName("a pilot whose unit is badly hurt shows the strain, in their own way")
    void badlyHurtPilotShowsStrain() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);

        for (int location = 0; location < hatchet.locations(); location++) {
            hatchet.setArmor(0, location);
        }
        hatchet.setInternal(0, Mek.LOC_LEFT_ARM);
        hatchet.setInternal(0, Mek.LOC_RIGHT_ARM);
        hatchet.setInternal(0, Mek.LOC_LEFT_LEG);
        hatchet.setInternal(0, Mek.LOC_RIGHT_LEG);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, prompts.size());
        assertTrue(prompts.getFirst().contains("whichever fits their personality"), prompts.getFirst());
        assertTrue(prompts.getFirst().contains("a brutal hit"), "losing that much in one phase is a brutal hit");
    }

    @Test
    @DisplayName("a lightly scratched pilot gets no tone line")
    void scratchedPilotHasNoTone() {
        Entity hatchet = mek(game, players, "Hatchet");
        chatter(0);
        phase(GamePhase.FIRING);

        hatchet.setArmor(8, Mek.LOC_CENTER_TORSO);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, prompts.size());
        assertFalse(prompts.getFirst().contains("armor and structure left"));
        assertFalse(prompts.getFirst().contains("terrified"));
    }

    @Test
    @DisplayName("a report for a unit no longer in the game says nothing and does not throw")
    void unknownUnitIsIgnored() {
        chatter(99);

        assertDoesNotThrow(() -> game.processGameEvent(new GameWithdrawalEvent(this, 9999, false)));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("a pilot on fire screams, once a round for as long as the fire lasts")
    void burningPilotScreamsOncePerRound() {
        Entity kenji = mek(game, opFor, "Kenji");
        chatter(99);
        phase(GamePhase.MOVEMENT);

        kenji.infernos.add(InfernoTracker.STANDARD_ROUND, 1);
        phase(GamePhase.MOVEMENT_REPORT);
        phase(GamePhase.FIRING);
        phase(GamePhase.FIRING_REPORT);

        assertEquals(1, delivered.size(), "one scream for the round, not one a phase");
        assertTrue(prompts.getFirst().contains("is on fire"));
        assertTrue(prompts.getFirst().contains(ChatterTone.SCREAMING));

        game.setCurrentRound(game.getCurrentRound() + 1);
        phase(GamePhase.MOVEMENT);
        phase(GamePhase.MOVEMENT_REPORT);
        assertEquals(2, delivered.size(), "still burning the next round, so another scream");
    }

    @Test
    @DisplayName("enemy pilots who speak their faction's language use it; the rest speak English")
    void enemyPilotsSpeakTheEnemyLanguage() {
        List<Entity> enemies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            enemies.add(mek(game, opFor, "Pilot" + i));
        }
        PilotChatter chatter = chatter(0);
        chatter.sideLanguages("Japanese", "German");

        enemies.forEach(enemy -> reports(enemy, false));

        assertEquals(enemies.size(), prompts.size());
        int speakers = 0;
        for (int i = 0; i < enemies.size(); i++) {
            boolean speaks = ChatterLanguage.speaksOwnLanguage("battle:Battle A:" + enemies.get(i).getId());
            assertEquals(speaks, prompts.get(i).contains("speak Japanese"), "pilot " + i);
            assertFalse(prompts.get(i).contains("German"), "enemy pilots never take the employer's language");
            speakers += speaks ? 1 : 0;
        }
        assertTrue((speakers > 0) && (speakers < enemies.size()), "a minority speak it, not none or all");
    }

    @Test
    @DisplayName("allied pilots take the employer's language")
    void alliedPilotsSpeakTheEmployerLanguage() {
        List<Entity> allies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            allies.add(mek(game, players, "Ally" + i));
        }
        PilotChatter chatter = chatter(0);
        chatter.sideLanguages("Japanese", "German");

        allies.forEach(ally -> reports(ally, false));

        for (int i = 0; i < allies.size(); i++) {
            boolean speaks = ChatterLanguage.speaksOwnLanguage("battle:Battle A:" + allies.get(i).getId());
            assertEquals(speaks, prompts.get(i).contains("speak German"), "ally " + i);
        }
    }

    @Test
    @DisplayName("even a speaker uses English when the line roll says so")
    void speakersStillUseEnglishSometimes() {
        List<Entity> enemies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            enemies.add(mek(game, opFor, "Pilot" + i));
        }
        PilotChatter chatter = chatter(99);
        chatter.sideLanguages("Japanese", null);

        enemies.forEach(enemy -> reports(enemy, false));

        assertTrue(prompts.stream().noneMatch(prompt -> prompt.contains("speak Japanese")));
    }
}
