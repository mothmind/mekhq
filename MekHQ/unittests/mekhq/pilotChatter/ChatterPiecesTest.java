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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

import megamek.common.Player;
import megamek.common.annotations.Nullable;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.enums.SkillLevel;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.options.PilotOptions;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import mekhq.campaign.Campaign;
import mekhq.campaign.force.PlayerForce;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioObjective;
import mekhq.campaign.personnel.Award;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.PersonAwardController;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.personnel.skills.Skills;
import mekhq.campaign.unit.Unit;
import mekhq.pilotChatter.BattleEventDetector.Trigger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the pieces of pilot chatter: who speaks, what they are told, the journal, and who hears them.
 */
class ChatterPiecesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    private static Trigger trigger(int entityId, ChatterEvent event) {
        return new Trigger(entityId, event, "", List.of());
    }

    // region Policy

    @Test
    @DisplayName("the big moments always get a line, whatever the dice say")
    void bigMomentsAlwaysSpeak() {
        List<Trigger> chosen = ChatterPolicy.choose(List.of(trigger(1, ChatterEvent.KILL),
              trigger(2, ChatterEvent.POSE), trigger(3, ChatterEvent.DESTROYED)), bound -> bound - 1, Chattiness.QUIET);

        assertEquals(3, chosen.size());
    }

    @Test
    @DisplayName("smaller moments speak when the roll comes in under their chance")
    void smallerMomentsRoll() {
        List<Trigger> triggers = List.of(trigger(1, ChatterEvent.DAMAGED), trigger(2, ChatterEvent.IN_ACTION));

        assertEquals(2, ChatterPolicy.choose(triggers, bound -> 0, Chattiness.CHATTY).size());
        assertEquals(List.of(1), ChatterPolicy.choose(triggers, bound -> 30, Chattiness.CHATTY).stream()
                                       .map(Trigger::entityId).toList(),
              "30 beats in-action's 25% but not damage's 45%");
        assertTrue(ChatterPolicy.choose(triggers, bound -> 99, Chattiness.CHATTY).isEmpty());
    }

    @Test
    @DisplayName("the chat is capped per phase, big moments first and never cut")
    void capped() {
        List<Trigger> triggers = new ArrayList<>();
        for (int id = 0; id < 20; id++) {
            triggers.add(trigger(id, ChatterEvent.IN_ACTION));
        }
        triggers.add(trigger(99, ChatterEvent.KILL));

        List<Trigger> chosen = ChatterPolicy.choose(triggers, bound -> 0, Chattiness.CHATTY);

        assertEquals(Chattiness.CHATTY.maxLines(), chosen.size());
        assertEquals(99, chosen.getFirst().entityId(), "the kill goes first");
        assertEquals(Chattiness.NORMAL.maxLines(),
              ChatterPolicy.choose(triggers, bound -> 0, Chattiness.NORMAL).size());
    }

    @Test
    @DisplayName("quiet pilots speak only at the big moments; normal ones half as often as chatty")
    void chattiness() {
        List<Trigger> triggers = List.of(trigger(1, ChatterEvent.KILL), trigger(2, ChatterEvent.DAMAGED),
              trigger(3, ChatterEvent.PILOT_HURT));

        assertEquals(List.of(1), ChatterPolicy.choose(triggers, bound -> 0, Chattiness.QUIET).stream()
                                       .map(Trigger::entityId).toList());
        assertEquals(22, Chattiness.NORMAL.chanceOf(ChatterEvent.DAMAGED));
        assertEquals(35, Chattiness.NORMAL.chanceOf(ChatterEvent.PILOT_HURT));
        assertEquals(100, Chattiness.QUIET.chanceOf(ChatterEvent.HEADSHOT));
        assertEquals(Chattiness.NORMAL, Chattiness.parse("normal"));
        assertEquals(Chattiness.CHATTY, Chattiness.parse("garbled"), "an unreadable setting falls back to chatty");
        assertEquals(0, new ChatterTuning(Chattiness.QUIET, true, -5).loreChance());
        assertEquals(100, new ChatterTuning(Chattiness.QUIET, true, 250).loreChance());
    }

    // endregion Policy

    // region Journal

    @Test
    @DisplayName("the journal keeps every line, survives being reopened, and finds a pilot's recent lines")
    void journalRoundTrip() {
        Path file = PilotJournal.fileFor(tempDir, "Dead Reckoning");
        PilotJournal journal = new PilotJournal(file);
        for (int i = 1; i <= 8; i++) {
            journal.append(new PilotJournal.Entry("t" + i, "3067-01-01", (i <= 4) ? "Battle A" : "Battle B", i,
                  (i % 2 == 0) ? "person:hatchet" : "person:grin", (i % 2 == 0) ? "Hatchet" : "Grin", "KILL",
                  "line " + i, 1, "", (i == 8) ? List.of(0, 2) : null));
        }

        PilotJournal reopened = new PilotJournal(file);

        assertEquals(List.of("line 4", "line 6", "line 8"),
              reopened.recentBy("person:hatchet", 3).stream().map(PilotJournal.Entry::line).toList(),
              "a pilot's lines carry across battles");
        assertEquals(List.of("line 7", "line 8"),
              reopened.recentInBattle("Battle B", 2).stream().map(PilotJournal.Entry::line).toList());
        assertEquals(List.of(0, 2), reopened.recentInBattle("Battle B", 1).getFirst().heardBy(),
              "who heard a line survives the round trip");
        assertEquals(List.of("line 6", "line 7"),
              reopened.recentInBattle("Battle B", 2, entry -> entry.heardBy() == null).stream()
                    .map(PilotJournal.Entry::line).toList(), "the filter is applied before the last lines are taken");
        assertEquals("Dead Reckoning - pilot journal.jsonl", file.getFileName().toString());
    }

    @Test
    @DisplayName("a damaged journal line is skipped rather than losing the rest")
    void journalSkipsBadLines() throws IOException {
        Path file = tempDir.resolve("journal.jsonl");
        new PilotJournal(file).append(new PilotJournal.Entry("t", "d", "b", 1, "k", "s", "KILL", "kept", 1, "", null));
        Files.writeString(file, "{not json\n", java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, new PilotJournal(file).recentBy("k", 10).size());
    }

    @Test
    @DisplayName("a campaign name that is no good as a file name is made safe")
    void safeFileName() {
        assertEquals("A_B_ - pilot journal.jsonl",
              PilotJournal.fileFor(tempDir, "A/B?").getFileName().toString());
    }

    // endregion Journal

    // region Dossier and prompt

    private static Entity mek(Game game, Player owner, String pilotName) {
        BipedMek mek = new BipedMek();
        mek.setChassis("Atlas");
        mek.setModel("AS7-D");
        mek.setWeight(100.0);
        mek.setCrew(new Crew(CrewType.SINGLE));
        mek.getCrew().setName(pilotName, 0);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    @Test
    @DisplayName("an enemy pilot gets a sketch, a faction, and is told to invent a persona")
    void enemyDossier() {
        Game game = new Game();
        Player opFor = new Player(1, "OpFor");
        opFor.setTeam(2);
        game.addPlayer(1, opFor);
        Entity enemy = mek(game, opFor, "Kenji Tanaka");

        PilotDossier dossier = PilotDossier.forOtherPilot(enemy, "Battle A", "Draconis Combine", null);

        assertEquals("Kenji Tanaka (Atlas AS7-D)", dossier.callName());
        assertFalse(dossier.campaignPilot());
        assertTrue(dossier.sheet().contains("enemy pilot, fighting for Draconis Combine"));
        assertTrue(dossier.sheet().contains("invent a fitting persona"));
        assertEquals("battle:Battle A:" + enemy.getId(), dossier.key(), "enemies keep continuity within a battle");
    }

    @Test
    @DisplayName("an unrecorded pilot is friend or foe of whoever fields the campaign's units, whatever their team")
    void sideFollowsTheCampaign() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        players.setTeam(2);
        Player allies = new Player(1, "Allies");
        allies.setTeam(2);
        Player opFor = new Player(2, "OpFor");
        opFor.setTeam(1);
        game.addPlayer(0, players);
        game.addPlayer(1, allies);
        game.addPlayer(2, opFor);
        Entity ours = mek(game, players, "Natasha Kerensky");
        UUID unitId = UUID.randomUUID();
        ours.setExternalIdAsString(unitId.toString());
        Campaign campaign = mock(Campaign.class);
        when(campaign.getUnit(unitId)).thenReturn(mock(Unit.class));
        Entity ally = mek(game, allies, "Marcus Hale");
        Entity enemy = mek(game, opFor, "Kenji Tanaka");

        Player campaignSide = PilotDossier.campaignSide(game, campaign);

        assertEquals(players.getId(), campaignSide.getId());
        assertTrue(PilotDossier.forOtherPilot(ally, "B", "Draconis Combine", campaignSide).sheet()
                         .contains("an allied pilot"), "team 2 is the players' side here");
        assertTrue(PilotDossier.forOtherPilot(enemy, "B", "Draconis Combine", campaignSide).sheet()
                         .contains("an enemy pilot, fighting for Draconis Combine"), "and team 1 is the enemy");
        assertNull(PilotDossier.campaignSide(game, mock(Campaign.class)), "no campaign units, no campaign side");
    }

    @Test
    @DisplayName("a campaign pilot gets their MekHQ record and keeps continuity across battles")
    void campaignDossier() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        players.setTeam(1);
        game.addPlayer(0, players);
        Entity atlas = mek(game, players, "Natasha Kerensky");
        UUID unitId = UUID.randomUUID();
        atlas.setExternalIdAsString(unitId.toString());

        Person person = mock(Person.class);
        UUID personId = UUID.randomUUID();
        when(person.getId()).thenReturn(personId);
        when(person.getCallsign()).thenReturn("Black Widow");
        when(person.getFullName()).thenReturn("Natasha Kerensky");
        when(person.getFullTitle()).thenReturn("Colonel Natasha Kerensky");
        when(person.getBiography()).thenReturn("<p>Led the <b>Black Widow Company</b>.</p>");
        when(person.getPersonalityDescription()).thenReturn("Never backs down from a fight.");
        when(person.getGender()).thenReturn(megamek.common.enums.Gender.FEMALE);
        when(person.getHits()).thenReturn(1);
        Unit unit = mock(Unit.class);
        when(unit.getCommander()).thenReturn(person);
        PlayerForce playerForce = mock(PlayerForce.class);
        when(playerForce.getName()).thenReturn("Wolf's Dragoons");
        Campaign campaign = mock(Campaign.class);
        when(campaign.getUnit(unitId)).thenReturn(unit);
        when(campaign.getPlayerForce()).thenReturn(playerForce);
        when(campaign.getKillsFor(personId)).thenReturn(List.of());

        PilotDossier dossier = PilotDossier.forEntity(atlas, campaign, "Battle A", "Draconis Combine", players);

        assertTrue(dossier.campaignPilot());
        assertEquals("person:" + personId, dossier.key());
        assertEquals("Black Widow (Atlas AS7-D)", dossier.callName());
        assertTrue(dossier.sheet().contains("Colonel Natasha Kerensky, callsign \"Black Widow\""));
        assertTrue(dossier.sheet().contains("Background: Led the Black Widow Company ."));
        assertTrue(dossier.sheet().contains("Character: Never backs down from a fight."));
        assertTrue(dossier.sheet().contains("Pronouns: she/her"), "so the model doesn't misgender her");
        assertEquals("she/her", dossier.pronouns(), "and so later speakers see them beside her lines");
        assertTrue(dossier.sheet().contains("Wolf's Dragoons"));
        assertTrue(dossier.sheet().contains("1 wound(s)"));
    }

    @Test
    @DisplayName("the prompt carries the pilot, what happened, their past lines and the battle's chatter")
    void prompt() {
        PilotDossier dossier = new PilotDossier("person:x", "Hatchet (Atlas AS7-D)", "Name: Hatchet", true, "she/her");
        Trigger kill = new Trigger(1, ChatterEvent.KILL, "They destroyed Locust LCT-1V.",
              List.of("Atlas fires Gauss Rifle at Locust, hits."));
        List<PilotJournal.Entry> own = List.of(new PilotJournal.Entry("t", "d", "b", 1, "person:x", "Hatchet",
              "DAMAGED", "Is that all you've got?", 1, "", null));
        List<PilotJournal.Entry> battle = List.of(new PilotJournal.Entry("t", "d", "b", 1, "person:y", "Grin (Locust)",
              "IN_ACTION", "Coming in fast!", 2, "he/him", null));

        String prompt = ChatterPrompt.build(dossier, 1, kill, false, 3, own, battle);

        assertTrue(prompt.contains("Name: Hatchet"));
        assertTrue(prompt.contains("round 3"));
        assertTrue(prompt.contains("destroyed an enemy unit"));
        assertTrue(prompt.contains("They destroyed Locust LCT-1V."));
        assertTrue(prompt.contains("Atlas fires Gauss Rifle at Locust, hits."));
        assertTrue(prompt.contains("- Is that all you've got?"));
        assertTrue(prompt.contains("- [enemy] Grin (Locust, he/him): Coming in fast!"),
              "chatter from the other side is marked, so pilots don't take up the enemy's slogans");
        assertTrue(ChatterPrompt.build(dossier, 2, kill, false, 3, own, battle)
                         .contains("- [your side] Grin (Locust, he/him): Coming in fast!"));
        assertFalse(prompt.contains("last words"));
        assertTrue(ChatterPrompt.build(dossier, 1, kill, true, 3, List.of(), List.of()).contains("last words"));
        assertTrue(ChatterPrompt.SYSTEM.contains("Never mention map hexes"),
              "the model is told not to give away positions");
    }

    @Test
    @DisplayName("a pilot killed by a headshot is caught mid-sentence, not given last words")
    void headshotPrompt() {
        PilotDossier dossier = new PilotDossier("person:x", "Hatchet (Atlas AS7-D)", "Name: Hatchet", true, "she/her");
        Trigger headshot = new Trigger(1, ChatterEvent.HEADSHOT, "", List.of());

        String prompt = ChatterPrompt.build(dossier, 1, headshot, true, 3, List.of(), List.of());

        assertTrue(prompt.contains("was killed instantly by a shot to the head"));
        assertTrue(prompt.contains("never saw it coming"));
        assertTrue(prompt.contains("It will be cut off."));
        assertFalse(prompt.contains("last words"));
    }

    @Test
    @DisplayName("a campaign pilot's record says what each special ability does, and lists skills and decorations")
    void campaignDossierExtras() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        game.addPlayer(0, players);
        Entity atlas = mek(game, players, "Natasha Kerensky");
        PilotOptions options = new PilotOptions();
        IOption sniper = options.getOption(OptionsConstants.GUNNERY_SNIPER);
        sniper.setValue(true);
        IOption unused = options.getOption(OptionsConstants.PILOT_JUMPING_JACK);
        Person person = mock(Person.class);
        when(person.getId()).thenReturn(UUID.randomUUID());
        when(person.getCallsign()).thenReturn("Black Widow");
        when(person.getOptions(PilotOptions.LVL3_ADVANTAGES))
              .thenReturn(Collections.enumeration(List.of(sniper, unused)));
        Skills skills = mock(Skills.class);
        when(skills.getSkillNames()).thenReturn(List.of("Gunnery/Mek", "Leadership"));
        Skill leadership = mock(Skill.class);
        when(leadership.getSkillLevel(any())).thenReturn(SkillLevel.VETERAN);
        when(skills.getSkill("Leadership")).thenReturn(leadership);
        when(skills.getSkill("Gunnery/Mek")).thenReturn(mock(Skill.class));
        when(person.getSkills()).thenReturn(skills);
        Award medal = mock(Award.class);
        when(medal.getName()).thenReturn("Meritorious Service");
        when(medal.getQuantity()).thenReturn(3);
        Award ribbon = mock(Award.class);
        when(ribbon.getName()).thenReturn("Armed Forces");
        when(ribbon.getQuantity()).thenReturn(1);
        PersonAwardController awards = mock(PersonAwardController.class);
        when(awards.getAwards()).thenReturn(List.of(ribbon, medal));
        when(person.getAwardController()).thenReturn(awards);
        PlayerForce playerForce = mock(PlayerForce.class);
        when(playerForce.getName()).thenReturn("Wolf's Dragoons");
        Campaign campaign = mock(Campaign.class);
        when(campaign.getPlayerForce()).thenReturn(playerForce);

        String sheet = PilotDossier.forCampaignPilot(atlas, person, campaign).sheet();

        assertTrue(sheet.contains("Special abilities:\n- Sniper: " + sniper.getDescription()), sheet);
        assertFalse(sheet.contains("Jumping Jack"), "abilities the pilot lacks are left out");
        assertTrue(sheet.contains("Other skills: Leadership (" + SkillLevel.VETERAN + ")"), sheet);
        assertFalse(sheet.contains("Gunnery/Mek ("), "gunnery and piloting are already on the unit line");
        assertTrue(sheet.contains("Decorations: Meritorious Service \u00d73, Armed Forces"), sheet);
    }

    @Test
    @DisplayName("an ability is explained by MegaMek's rules text, else MekHQ's first sentence, else not at all")
    void abilityDescriptions() {
        IOption sniper = mock(IOption.class);
        when(sniper.getName()).thenReturn(OptionsConstants.GUNNERY_SNIPER);
        when(sniper.getDescription()).thenReturn("");
        IOption coordinator = mock(IOption.class);
        when(coordinator.getName()).thenReturn("admin_coordinator");
        when(coordinator.getDescription()).thenReturn("Liaises with allied forces. If this character is the commander, "
                                                            + "more happens.");
        IOption unknown = mock(IOption.class);
        when(unknown.getName()).thenReturn("house_rule");
        when(unknown.getDescription()).thenReturn("house_rule");

        assertEquals(new PilotOptions().getOption(OptionsConstants.GUNNERY_SNIPER).getDescription(),
              PilotDossier.abilityDescription(sniper), "MekHQ often leaves MegaMek's abilities blank");
        assertEquals("Liaises with allied forces.", PilotDossier.abilityDescription(coordinator));
        assertEquals("", PilotDossier.abilityDescription(unknown), "an option with no text reports its own key");
    }

    @Test
    @DisplayName("comrades go by callsign, unit and pronouns; a unit that is not the campaign's is nobody's comrade")
    void comrade() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        game.addPlayer(0, players);
        Entity atlas = mek(game, players, "Natasha Kerensky");
        UUID unitId = UUID.randomUUID();
        atlas.setExternalIdAsString(unitId.toString());
        Person person = mock(Person.class);
        when(person.getCallsign()).thenReturn("Black Widow");
        when(person.getGender()).thenReturn(megamek.common.enums.Gender.FEMALE);
        Unit unit = mock(Unit.class);
        when(unit.getCommander()).thenReturn(person);
        Campaign campaign = mock(Campaign.class);
        when(campaign.getUnit(unitId)).thenReturn(unit);

        assertEquals("Black Widow (Atlas AS7-D, she/her)", PilotDossier.comrade(atlas, campaign));
        assertNull(PilotDossier.comrade(mek(game, players, "Stranger"), campaign));
    }

    @Test
    @DisplayName("the briefing names the contract, its sides and the battle's objectives")
    void mission() {
        AbstractContract contract = mock(AbstractContract.class);
        when(contract.getName()).thenReturn("3132 - TC - Shaunavon Guerrilla Warfare");
        when(contract.getEmployerDisplayName()).thenReturn("Taurian Concordat");
        when(contract.getEnemyDisplayName()).thenReturn("Federated Suns");
        UUID missionId = UUID.randomUUID();
        Campaign campaign = mock(Campaign.class);
        when(campaign.getContract(missionId)).thenReturn(contract);
        ScenarioObjective hold = mock(ScenarioObjective.class);
        when(hold.getDescription()).thenReturn("Hold the ridge for 8 turns");
        ScenarioObjective blank = mock(ScenarioObjective.class);
        when(blank.getDescription()).thenReturn(" ");
        Scenario scenario = mock(Scenario.class);
        when(scenario.getMissionId()).thenReturn(missionId);
        when(scenario.getName()).thenReturn("Ridge Defense");
        when(scenario.getScenarioObjectives()).thenReturn(List.of(hold, blank));

        assertEquals("Contract: 3132 - TC - Shaunavon Guerrilla Warfare, for Taurian Concordat against Federated Suns."
                           + "\nThis battle: Ridge Defense. Objectives: Hold the ridge for 8 turns.",
              BattleBriefing.mission(campaign, scenario));
    }

    @Test
    @DisplayName("outsiders hear of the company by its public face, or by its force name when the lore has none")
    void outsiderBriefing() {
        CompanyLore lore = new CompanyLore("Secret story.", "A mercenary company.");

        assertEquals("WHO THEY ARE FIGHTING\nA mercenary company.\n\n",
              new BattleBriefing(lore, "Remnants", "").forOtherPilot(true));
        assertEquals("WHO THEY FIGHT ALONGSIDE\nRemnants\n\n",
              new BattleBriefing(CompanyLore.NONE, "Remnants", "").forOtherPilot(false));
        assertEquals("", BattleBriefing.NONE.forCampaignPilot(List.of()), "nothing to tell, nothing added");
    }

    @Test
    @DisplayName("a request to recall the company's story points at one side of it at a time, and needs a story")
    void recall() {
        BattleBriefing briefing = new BattleBriefing(new CompanyLore("Secret story.", ""), "Remnants", "");

        assertEquals("Something about this moment takes the pilot back to what the company has lost: bring one "
                           + "detail of it from THE COMPANY into the line.", briefing.recall(1));
        assertEquals(briefing.recall(1), briefing.recall(1 + BattleBriefing.RECALLED.size()));
        assertEquals("", BattleBriefing.NONE.recall(0));
    }

    @Test
    @DisplayName("the lore file gives our story to our pilots and our public face to everyone else")
    void lore() throws IOException {
        Path file = CompanyLore.fileFor(tempDir, "Comstar Explorer Remnants");
        Files.writeString(file, """
              # Company lore

              <!-- Pilot chatter reads this at the start of each battle. -->

              ## Our Story
              We are the last of the Explorer Corps.
              ### The long dark
              We went dark in the deep Periphery.

              ## Our public face
              A mercenary company.

              ## Notes for the GM
              Not for the pilots.
              """);

        CompanyLore lore = CompanyLore.read(file);

        assertEquals("Comstar Explorer Remnants - company lore.md", file.getFileName().toString());
        assertEquals("We are the last of the Explorer Corps. ### The long dark We went dark in the deep Periphery.",
              lore.story());
        assertEquals("A mercenary company.", lore.publicFace());
        assertEquals(CompanyLore.NONE, CompanyLore.read(tempDir.resolve("missing.md")));
        assertEquals(CompanyLore.MAX_STORY, CompanyLore.parse("## Our story\n" + "x".repeat(5000)).story().length(),
              "a long story is cut so it cannot crowd out the rest of the prompt");
    }

    // endregion Dossier and prompt

    // region Headshot cut-off

    @Test
    @DisplayName("a cut-off line keeps a few whole words, then half of the next long one, then a dash")
    void cutOff() {
        String line = "Tell the kids I went out swinging.";

        assertEquals("Tell\u2014", PilotChatter.cutOff(line, bound -> 0), "\"the\" is too short to split");
        assertEquals("Tell the ki\u2014", PilotChatter.cutOff(line, bound -> 1));
        assertEquals("Tell the kids\u2014", PilotChatter.cutOff(line, bound -> 2));
        assertEquals("Tell the kids I we\u2014", PilotChatter.cutOff(line, bound -> 3));
    }

    @Test
    @DisplayName("a cut never keeps more than four whole words, and always drops at least one")
    void cutOffBounds() {
        List<Integer> bounds = new ArrayList<>();

        assertEquals("one two three four fi\u2014", PilotChatter.cutOff("one two three four five six seven", bound -> {
            bounds.add(bound);
            return bound - 1;
        }));
        assertEquals(List.of(PilotChatter.MAX_WORDS_BEFORE_CUT), bounds);

        bounds.clear();
        assertEquals("Get do\u2014", PilotChatter.cutOff("Get down", bound -> {
            bounds.add(bound);
            return bound - 1;
        }));
        assertEquals(List.of(1), bounds, "a two-word line keeps only its first word whole");
    }

    @Test
    @DisplayName("a cut drops the punctuation it lands on, and a single word is cut in half")
    void cutOffPunctuation() {
        assertEquals("Wait\u2014", PilotChatter.cutOff("Wait, no!", bound -> 0));
        assertEquals("Cont\u2014", PilotChatter.cutOff("Contact!", bound -> 0));
        assertEquals("Hold the li\u2014", PilotChatter.cutOff("Hold the line\u2014", bound -> 1));
    }

    // endregion Headshot cut-off

    // region Audience

    private static Game doubleBlindGame() {
        Game game = new Game();
        Player players = new Player(0, "Players");
        players.setTeam(1);
        Player allies = new Player(1, "Allies");
        allies.setTeam(1);
        allies.setBot(true);
        Player opFor = new Player(2, "OpFor");
        opFor.setTeam(2);
        opFor.setBot(true);
        game.addPlayer(0, players);
        game.addPlayer(1, allies);
        game.addPlayer(2, opFor);
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        return game;
    }

    private static List<Integer> ids(@Nullable List<Player> players) {
        return (players == null) ? null : players.stream().map(Player::getId).toList();
    }

    @Test
    @DisplayName("without double-blind everyone hears and the pilot knows the whole battle")
    void audienceWithoutDoubleBlind() {
        Game game = doubleBlindGame();
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(false);
        Entity speaker = mek(game, game.getPlayer(2), "Kenji");

        ChatAudience audience = ChatAudience.of(game, speaker);

        assertNull(audience.listeners());
        assertNull(audience.viewer());
        assertTrue(audience.isHeard());
    }

    @Test
    @DisplayName("under double-blind a pilot on the players' side is heard by that side only, even when the enemy sees")
    void audienceOwnSide() {
        Game game = doubleBlindGame();
        Entity allied = mek(game, game.getPlayer(1), "Marcus");
        allied.setWhoCanSee(new Vector<>(List.of(game.getPlayer(2))));

        ChatAudience audience = ChatAudience.of(game, allied);

        assertEquals(List.of(0, 1), ids(audience.listeners()));
        assertEquals(0, audience.viewer().getId(), "an allied bot speaks from what the human beside it knows");
    }

    @Test
    @DisplayName("under double-blind an enemy is heard only by the humans who can see it, and knows only what they do")
    void audienceEnemy() {
        Game game = doubleBlindGame();
        Entity enemy = mek(game, game.getPlayer(2), "Kenji");

        assertFalse(ChatAudience.of(game, enemy).isHeard(), "no human sees it, so the line is not worth writing");

        enemy.setWhoCanSee(new Vector<>(List.of(game.getPlayer(0))));
        ChatAudience audience = ChatAudience.of(game, enemy);

        assertTrue(audience.isHeard());
        assertEquals(List.of(0, 2), ids(audience.listeners()));
        assertEquals(0, audience.viewer().getId());
    }

    @Test
    @DisplayName("under double-blind a player who may see everything hears everything")
    void audienceSeeAll() {
        Game game = doubleBlindGame();
        Player gm = new Player(3, "GM");
        gm.setTeam(3);
        gm.setGameMaster(true);
        gm.setSeeAll(true);
        game.addPlayer(3, gm);
        Entity enemy = mek(game, game.getPlayer(2), "Kenji");
        enemy.setWhoCanSee(new Vector<>(List.of(game.getPlayer(0))));

        assertTrue(ids(ChatAudience.of(game, enemy).listeners()).contains(3));
        assertTrue(ids(ChatAudience.of(game, mek(game, game.getPlayer(0), "Hatchet")).listeners()).contains(3));
    }

    @Test
    @DisplayName("a pilot may draw only on lines their viewer and their own side heard; untracked lines are fair game")
    void heard() {
        Player viewer = new Player(0, "Players");
        Player opFor = new Player(2, "OpFor");
        ChatAudience ours = new ChatAudience(List.of(viewer), viewer, viewer);
        ChatAudience enemy = new ChatAudience(List.of(viewer, opFor), viewer, opFor);

        assertTrue(ours.heard(entryHeardBy(null)));
        assertTrue(ours.heard(entryHeardBy(List.of(0, 2))));
        assertFalse(ours.heard(entryHeardBy(List.of(2))));
        assertTrue(enemy.heard(entryHeardBy(List.of(0, 2))));
        assertFalse(enemy.heard(entryHeardBy(List.of(0))), "an enemy never hears the players' own comms");
        assertTrue(ChatAudience.EVERYONE.heard(entryHeardBy(List.of(2))), "without double-blind nothing is hidden");
    }

    private static PilotJournal.Entry entryHeardBy(@Nullable List<Integer> heardBy) {
        return new PilotJournal.Entry("t", "d", "b", 1, "k", "s", "KILL", "line", 2, "", heardBy);
    }

    // endregion Audience
}
