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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import megamek.Version;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.common.board.Board;
import mekhq.campaign.Campaign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Tests for the secret ambush force: where it comes from, how big it is, and that the secret survives a campaign
 * save.
 */
class SecretAmbushTest {

    @Test
    @DisplayName("the ambush comes from behind the players or from either flank")
    void rearOrFlank() {
        assertEquals(Board.START_S, SecretAmbush.ambushEdge(Board.START_S, 0), "rear: the players' own edge");
        assertEquals(Board.START_W, SecretAmbush.ambushEdge(Board.START_S, 1));
        assertEquals(Board.START_E, SecretAmbush.ambushEdge(Board.START_S, 2));

        assertEquals(Board.START_NE, SecretAmbush.ambushEdge(Board.START_NW, 1), "flanks wrap round the compass");
        assertEquals(Board.START_SW, SecretAmbush.ambushEdge(Board.START_NW, 2));
        assertEquals(Board.START_N, SecretAmbush.ambushEdge(Board.START_W, 1));
    }

    @Test
    @DisplayName("players without a single deployment edge are ambushed from any edge")
    void noSingleEdge() {
        assertEquals(Board.START_EDGE, SecretAmbush.ambushEdge(Board.START_ANY, 0));
        assertEquals(Board.START_EDGE, SecretAmbush.ambushEdge(Board.START_CENTER, 1));
        assertEquals(Board.START_EDGE, SecretAmbush.ambushEdge(Board.START_EDGE, 2));
    }

    @Test
    @DisplayName("the ambush is budgeted at about one enemy formation's share of the players' strength")
    void aboutOneLance() {
        assertEquals(1.0 / 3.0, SecretAmbush.forceMultiplier(4, 12), 1e-9, "a lance sprung on a company");
        assertEquals(0.5, SecretAmbush.forceMultiplier(4, 8), 1e-9);
        assertEquals(1.0, SecretAmbush.forceMultiplier(4, 4), 1e-9, "a lance sprung on a lance matches it");
        assertEquals(1.0, SecretAmbush.forceMultiplier(5, 3), 1e-9, "never more than the players' own strength");
        assertEquals(1.0, SecretAmbush.forceMultiplier(4, 0), 1e-9);
    }

    @Test
    @DisplayName("the secret ambush force is found among the scenario's bot forces")
    void findTheAmbush() {
        Scenario scenario = new Scenario();
        Campaign campaign = mock(Campaign.class);
        BotForce opFor = new BotForce("OpFor", 2, Board.START_N, new ArrayList<>());
        BotForce ambush = new BotForce("Ambushers", 2, Board.START_S, new ArrayList<>());
        ambush.setSecretAmbush(true);

        scenario.addBotForce(opFor, campaign);
        assertNull(SecretAmbush.find(scenario));

        scenario.addBotForce(ambush, campaign);
        assertSame(ambush, SecretAmbush.find(scenario));
    }

    @Test
    @DisplayName("the secret flag survives a campaign save, and forces saved before it existed load as ordinary")
    void secretFlagRoundTrips() throws Exception {
        BotForce ambush = new BotForce("Ambushers", 2, Board.START_S, new ArrayList<>());
        ambush.setBehaviorSettings(new BehaviorSettings());
        ambush.setSecretAmbush(true);

        assertTrue(loadBotForce(write(ambush)).isSecretAmbush());
        assertFalse(loadBotForce("<botForce><team>2</team></botForce>").isSecretAmbush());
        assertTrue(ambush.clone().isSecretAmbush());
    }

    @Test
    @DisplayName("the planned ambush round survives a campaign save")
    void ambushRoundRoundTrips() throws Exception {
        AtBDynamicScenario scenario = new AtBDynamicScenario();
        scenario.setTemplate(new ScenarioTemplate());
        scenario.setSecretAmbushRound(9);

        StringWriter stringWriter = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
            scenario.writeToXML(printWriter, 0);
        }
        Element root = parse(stringWriter.toString());
        AtBDynamicScenario loaded = (AtBDynamicScenario) Scenario.generateInstanceFromXML(root,
              mock(Campaign.class), new Version());

        assertEquals(9, loaded.getSecretAmbushRound());
        assertEquals(0, new AtBDynamicScenario().getSecretAmbushRound(), "no ambush unless one was planned");
    }

    @Test
    @DisplayName("an objective against all enemy forces leaves out the secret ambush")
    void objectivesLeaveOutTheAmbush() {
        Campaign campaign = mock(Campaign.class);
        AtBDynamicScenario scenario = new AtBDynamicScenario();
        scenario.setTemplate(new ScenarioTemplate());
        BotForce opFor = new BotForce("OpFor", 2, Board.START_N, new ArrayList<>());
        BotForce ambush = new BotForce("Ambushers", 2, Board.START_S, new ArrayList<>());
        ambush.setSecretAmbush(true);
        scenario.addBotForce(opFor, hostileTemplate("OpFor"), campaign);
        scenario.addBotForce(ambush, hostileTemplate(SecretAmbush.FORCE_NAME), campaign);

        ScenarioObjective destroyThemAll = new ScenarioObjective();
        destroyThemAll.addForce(ScenarioObjective.FORCE_SHORTCUT_ALL_ENEMY_FORCES);
        ScenarioObjective actual = AtBDynamicScenarioFactory.translateTemplateObjective(scenario, campaign,
              destroyThemAll);

        assertTrue(actual.getAssociatedForceNames().contains("OpFor"));
        assertFalse(actual.getAssociatedForceNames().contains("Ambushers"),
              "units that may never arrive must not count against the players");
    }

    private static ScenarioForceTemplate hostileTemplate(String name) {
        ScenarioForceTemplate template = new ScenarioForceTemplate();
        template.setForceName(name);
        template.setForceAlignment(ScenarioForceTemplate.ForceAlignment.Opposing.ordinal());
        template.setActualDeploymentZone(Board.START_N);
        return template;
    }

    private static String write(BotForce force) {
        StringWriter stringWriter = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
            force.writeToXML(printWriter, 0);
        }
        return stringWriter.toString();
    }

    private static BotForce loadBotForce(String xml) throws Exception {
        BotForce loaded = new BotForce();
        loaded.setBehaviorSettings(new BehaviorSettings());
        loaded.setFieldsFromXmlNode(parse(xml), new Version(), mock(Campaign.class));
        return loaded;
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return documentBuilder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
}
