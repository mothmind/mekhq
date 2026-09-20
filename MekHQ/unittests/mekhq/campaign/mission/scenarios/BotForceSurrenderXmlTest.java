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
package mekhq.campaign.mission.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import megamek.Version;
import megamek.client.bot.princess.BehaviorSettings;
import mekhq.campaign.Campaign;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * The surrender settings must survive a campaign save and a {@link BotForce#clone()}, and a bot force saved before
 * they existed must load with surrender off.
 */
class BotForceSurrenderXmlTest {

    @Test
    void surrenderSettingsRoundTripThroughXml() throws Exception {
        BotForce force = new BotForce();
        force.setBehaviorSettings(new BehaviorSettings());
        force.getBehaviorSettings().setAllowSurrender(true);
        force.getBehaviorSettings().setResolveIndex(8);

        BotForce loaded = load(writeToXml(force));

        assertTrue(loaded.getBehaviorSettings().isAllowSurrender());
        assertEquals(8, loaded.getBehaviorSettings().getResolveIndex());
    }

    @Test
    void aForceSavedWithoutTheTagsLoadsWithSurrenderOff() throws Exception {
        BotForce loaded = load("<botForce><behaviorSettings><braveryIndex>7</braveryIndex></behaviorSettings></botForce>");

        assertFalse(loaded.getBehaviorSettings().isAllowSurrender());
        assertEquals(5, loaded.getBehaviorSettings().getResolveIndex());
        assertEquals(7, loaded.getBehaviorSettings().getBraveryIndex());
    }

    @Test
    void cloneCarriesTheSurrenderSettings() {
        BotForce force = new BotForce();
        force.setBehaviorSettings(new BehaviorSettings());
        force.getBehaviorSettings().setAllowSurrender(true);
        force.getBehaviorSettings().setResolveIndex(2);

        BotForce copy = force.clone();

        assertTrue(copy.getBehaviorSettings().isAllowSurrender());
        assertEquals(2, copy.getBehaviorSettings().getResolveIndex());
    }

    private static String writeToXml(BotForce force) {
        StringWriter stringWriter = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
            force.writeToXML(printWriter, 0);
        }
        return stringWriter.toString();
    }

    private static BotForce load(String xml) throws Exception {
        DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Element root = documentBuilder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        BotForce loaded = new BotForce();
        loaded.setBehaviorSettings(new BehaviorSettings());
        loaded.setFieldsFromXmlNode(root, new Version(), mock(Campaign.class));
        return loaded;
    }
}
