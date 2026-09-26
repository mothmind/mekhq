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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.Report;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import mekhq.pilotChatter.BattleEventDetector.Trigger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for working out what happened to each pilot since the last report phase.
 */
class BattleEventDetectorTest {

    private Game game;
    private Player players;
    private Player opFor;
    private BattleEventDetector detector;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        players = new Player(0, "Players");
        players.setTeam(1);
        opFor = new Player(1, "OpFor");
        opFor.setTeam(2);
        game.addPlayer(0, players);
        game.addPlayer(1, opFor);
        detector = new BattleEventDetector();
    }

    private Entity mek(String model, Player owner) {
        BipedMek mek = new BipedMek();
        mek.setChassis("Test");
        mek.setModel(model);
        mek.setWeight(50.0);
        mek.autoSetInternal();
        for (int location = 0; location < mek.locations(); location++) {
            mek.initializeArmor(10, location);
        }
        mek.setCrew(new Crew(CrewType.SINGLE));
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private Map<Integer, ChatterEvent> detect(List<Report> reports, boolean inAction) {
        return detector.detect(game, reports, inAction).stream()
                     .collect(Collectors.toMap(Trigger::entityId, Trigger::event));
    }

    @Test
    @DisplayName("nothing happened, nothing to say")
    void quietPhase() {
        mek("A", players);
        detector.prime(game);

        assertTrue(detect(List.of(), false).isEmpty());
    }

    @Test
    @DisplayName("a unit that loses armour took hits, and the detail says how much")
    void damage() {
        Entity target = mek("A", players);
        detector.prime(game);
        target.setArmor(4, Mek.LOC_CENTER_TORSO);

        List<Trigger> triggers = detector.detect(game, List.of(), false);

        assertEquals(ChatterEvent.DAMAGED, triggers.getFirst().event());
        assertEquals("They took 6 damage.", triggers.getFirst().detail());
    }

    @Test
    @DisplayName("a destroyed unit is destroyed, and its killer scored a kill")
    void destroyedAndKill() {
        Entity killer = mek("Killer", players);
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        killer.addKill(victim);
        victim.setDestroyed(true);

        List<Trigger> triggers = detector.detect(game, List.of(), false);
        Map<Integer, ChatterEvent> events = triggers.stream()
                                                  .collect(Collectors.toMap(Trigger::entityId, Trigger::event));

        assertEquals(ChatterEvent.DESTROYED, events.get(victim.getId()));
        assertEquals(ChatterEvent.KILL, events.get(killer.getId()));
        assertEquals("They destroyed " + victim.getShortName() + ".",
              triggers.stream().filter(t -> t.entityId() == killer.getId()).findFirst().orElseThrow().detail());
    }

    @Test
    @DisplayName("a pilot killed in a phase that hit the head is a headshot, and the killer hears how it happened")
    void headshot() {
        Entity killer = mek("Killer", players);
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        killer.addKill(victim);
        victim.setArmor(0, Mek.LOC_HEAD);
        victim.getCrew().setDoomed(true);
        victim.setDoomed(true);

        List<Trigger> triggers = detector.detect(game, List.of(), false);
        Map<Integer, ChatterEvent> events = triggers.stream()
                                                  .collect(Collectors.toMap(Trigger::entityId, Trigger::event));

        assertEquals(ChatterEvent.HEADSHOT, events.get(victim.getId()));
        assertEquals(ChatterEvent.KILL, events.get(killer.getId()));
        assertEquals("They destroyed " + victim.getShortName() + " with a shot to the head that killed its pilot.",
              triggers.stream().filter(t -> t.entityId() == killer.getId()).findFirst().orElseThrow().detail());
    }

    @Test
    @DisplayName("a pilot who dies without a hit to the head is not a headshot")
    void deathElsewhere() {
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        victim.setArmor(0, Mek.LOC_CENTER_TORSO);
        victim.getCrew().setDoomed(true);
        victim.setDoomed(true);

        assertEquals(ChatterEvent.DESTROYED, detect(List.of(), false).get(victim.getId()));
    }

    @Test
    @DisplayName("a hit to the head that leaves the pilot alive is not a headshot, even if the unit is destroyed")
    void headHitPilotLives() {
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        victim.setArmor(5, Mek.LOC_HEAD);
        victim.setInternal(0, Mek.LOC_CENTER_TORSO);
        victim.setDoomed(true);

        assertEquals(ChatterEvent.DESTROYED, detect(List.of(), false).get(victim.getId()));
    }

    @Test
    @DisplayName("a headshot victim removed from play during the phase is still a headshot")
    void headshotRemovedFromPlay() {
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        victim.setArmor(0, Mek.LOC_HEAD);
        victim.getCrew().setDoomed(true);
        victim.setDestroyed(true);
        game.removeEntity(victim.getId(), megamek.common.interfaces.IEntityRemovalConditions.REMOVE_SALVAGEABLE);

        assertEquals(ChatterEvent.HEADSHOT, detect(List.of(), false).get(victim.getId()));
    }

    @Test
    @DisplayName("under double-blind a kill is named only if the killer's side has seen the victim")
    void unseenKill() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        Entity spotter = mek("Spotter", players);
        Entity blindShot = mek("Blind Shot", players);
        Entity seen = mek("Seen", opFor);
        Entity unseen = mek("Unseen", opFor);
        seen.addBeenSeenBy(players);
        detector.prime(game);
        spotter.addKill(seen);
        seen.setDestroyed(true);
        blindShot.addKill(unseen);
        unseen.setDestroyed(true);

        Map<Integer, String> details = detector.detect(game, List.of(), false, entity -> players).stream()
                                             .collect(Collectors.toMap(Trigger::entityId, Trigger::detail));

        assertEquals("They destroyed " + seen.getShortName() + ".", details.get(spotter.getId()));
        assertEquals("They destroyed an enemy unit their side never saw.", details.get(blindShot.getId()));
    }

    @Test
    @DisplayName("under double-blind a report meant for another player reaches the prompt with its secrets blanked")
    void reportAsSeen() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        Entity unit = mek("A", players);
        detector.prime(game);
        Report secret = new Report(1050, Report.PLAYER);
        secret.subject = unit.getId();
        secret.player = opFor.getId();
        secret.add("Kenji's flank", true);

        assertTrue(detector.detect(game, List.of(secret), true).getFirst().context().getFirst()
                         .contains("Kenji's flank"), "without a viewer the pilot knows everything");
        String seen = detector.detect(game, List.of(secret), true, entity -> players).getFirst().context().getFirst();
        assertTrue(seen.contains(BattleEventDetector.UNSEEN), seen);
        assertFalse(seen.contains("Kenji"), seen);

        game.getOptions().getOption(OptionsConstants.ADVANCED_SUPPRESS_ALL_DB_MESSAGES).setValue(true);
        assertTrue(detector.detect(game, List.of(secret), true, entity -> players).isEmpty(),
              "a game that suppresses hidden reports drops it altogether");
    }

    @Test
    @DisplayName("a unit removed from play during the phase still counts as destroyed")
    void removedFromPlay() {
        Entity killer = mek("Killer", players);
        Entity victim = mek("Victim", opFor);
        detector.prime(game);
        killer.addKill(victim);
        victim.setDestroyed(true);
        game.removeEntity(victim.getId(), megamek.common.interfaces.IEntityRemovalConditions.REMOVE_SALVAGEABLE);

        Map<Integer, ChatterEvent> events = detect(List.of(), false);

        assertEquals(ChatterEvent.DESTROYED, events.get(victim.getId()));
        assertEquals(ChatterEvent.KILL, events.get(killer.getId()));
    }

    @Test
    @DisplayName("an injured pilot, an ejection and a pose are each noticed")
    void crewEvents() {
        Entity hurt = mek("Hurt", players);
        Entity ejecting = mek("Ejecting", players);
        Entity poser = mek("Poser", players);
        detector.prime(game);
        hurt.getCrew().setHits(1, 0);
        ejecting.getCrew().setEjected(true);
        poser.setPosing(true);

        Map<Integer, ChatterEvent> events = detect(List.of(), false);

        assertEquals(ChatterEvent.PILOT_HURT, events.get(hurt.getId()));
        assertEquals(ChatterEvent.EJECTED, events.get(ejecting.getId()));
        assertEquals(ChatterEvent.POSE, events.get(poser.getId()));
    }

    @Test
    @DisplayName("an explosion inside the unit comes from the battle reports")
    void explosion() {
        Entity unit = mek("A", players);
        detector.prime(game);
        Report explosion = new Report(BattleEventDetector.EXPLOSION_REPORT);
        explosion.subject = unit.getId();

        assertEquals(ChatterEvent.INTERNAL_EXPLOSION, detect(List.of(explosion), false).get(unit.getId()));
    }

    @Test
    @DisplayName("each unit gets its single most important event")
    void mostImportantEventWins() {
        Entity unit = mek("A", players);
        detector.prime(game);
        unit.setArmor(0, Mek.LOC_HEAD);
        unit.getCrew().setHits(2, 0);

        assertEquals(ChatterEvent.PILOT_HURT, detect(List.of(), false).get(unit.getId()));
    }

    @Test
    @DisplayName("units the reports mention are in action in the movement and firing phases")
    void inAction() {
        Entity unit = mek("A", players);
        detector.prime(game);
        Report moved = new Report(2000);
        moved.subject = unit.getId();

        assertEquals(ChatterEvent.IN_ACTION, detect(List.of(moved), true).get(unit.getId()));
        assertTrue(detect(List.of(moved), false).isEmpty(), "outside those phases a mention is not action");
    }

    @Test
    @DisplayName("a unit seen for the first time says nothing about what came before")
    void newArrival() {
        detector.prime(game);
        mek("Late", players).setArmor(1, Mek.LOC_CENTER_TORSO);

        assertTrue(detect(List.of(), false).isEmpty());
    }

    @Test
    @DisplayName("each event is reported once, not again in the next phase")
    void reportedOnce() {
        Entity unit = mek("A", players);
        detector.prime(game);
        unit.setArmor(2, Mek.LOC_CENTER_TORSO);

        assertEquals(1, detect(List.of(), false).size());
        assertTrue(detect(List.of(), false).isEmpty());
    }
}
