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
package mekhq.campaign.base;

import java.io.PrintWriter;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import megamek.logging.MMLogger;
import mekhq.utilities.MHQXMLUtility;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One facility at a {@link PlayerBase}: its type, the level it has reached, and the level being built, if any.
 *
 * <p>A facility being upgraded keeps working at the level it has reached until the new one is finished.</p>
 */
public class BaseFacility {
    private static final MMLogger LOGGER = MMLogger.create(BaseFacility.class);

    private final BaseFacilityType type;
    private int level;
    private int targetLevel;
    private @Nullable LocalDate completionDate;

    public BaseFacility(BaseFacilityType type) {
        this.type = type;
    }

    public BaseFacilityType getType() {
        return type;
    }

    /** @return the level that has been finished: 0 if nothing has been built */
    public int getLevel() {
        return level;
    }

    /** @return the level under construction, or 0 if nothing is being built */
    public int getTargetLevel() {
        return isUnderConstruction() ? targetLevel : 0;
    }

    /** @return the day the level under construction is finished, or {@code null} if nothing is being built */
    public @Nullable LocalDate getCompletionDate() {
        return isUnderConstruction() ? completionDate : null;
    }

    public boolean isUnderConstruction() {
        return (targetLevel > level) && (completionDate != null);
    }

    public boolean isAtMaxLevel() {
        return level >= BaseFacilityType.MAX_LEVEL;
    }

    /** @return {@code true} if there is anything to save: a finished level or one under construction */
    boolean isWorthSaving() {
        return (level > 0) || isUnderConstruction();
    }

    /**
     * Starts building the next level. The caller has already checked there is a next level and that nothing is being
     * built, and has taken the payment.
     *
     * @param today the day construction starts
     */
    void startConstruction(LocalDate today) {
        targetLevel = level + 1;
        completionDate = today.plusDays(type.getBuildDays(targetLevel));
    }

    /**
     * Finishes the level under construction if its day has come.
     *
     * @return {@code true} if a level was finished
     */
    boolean completeIfDue(LocalDate today) {
        if (!isUnderConstruction() || today.isBefore(completionDate)) {
            return false;
        }
        level = targetLevel;
        targetLevel = 0;
        completionDate = null;
        return true;
    }

    void writeToXML(PrintWriter pw, int indent) {
        MHQXMLUtility.writeSimpleXMLOpenTag(pw, indent++, "facility");
        MHQXMLUtility.writeSimpleXMLTag(pw, indent, "type", type.name());
        MHQXMLUtility.writeSimpleXMLTag(pw, indent, "level", level);
        if (isUnderConstruction()) {
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "targetLevel", targetLevel);
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "completionDate", completionDate);
        }
        MHQXMLUtility.writeSimpleXMLCloseTag(pw, --indent, "facility");
    }

    /** @return the facility read from a {@code facility} node, or {@code null} if its type is not one we know */
    static @Nullable BaseFacility generateInstanceFromXML(Node wn) {
        BaseFacilityType type = null;
        int level = 0;
        int targetLevel = 0;
        LocalDate completionDate = null;
        NodeList nodes = wn.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String text = node.getTextContent().trim();
            try {
                switch (node.getNodeName().toLowerCase()) {
                    case "type" -> type = BaseFacilityType.valueOf(text);
                    case "level" -> level = Integer.parseInt(text);
                    case "targetlevel" -> targetLevel = Integer.parseInt(text);
                    case "completiondate" -> completionDate = MHQXMLUtility.parseDate(text);
                    default -> {
                    }
                }
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Ignoring '{}' in a base facility: {}", node.getNodeName(), ex.getMessage());
            }
        }
        if (type == null) {
            return null;
        }
        BaseFacility facility = new BaseFacility(type);
        facility.level = Math.max(0, Math.min(level, BaseFacilityType.MAX_LEVEL));
        if ((completionDate != null) && (targetLevel > facility.level)
                  && (targetLevel <= BaseFacilityType.MAX_LEVEL)) {
            facility.targetLevel = targetLevel;
            facility.completionDate = completionDate;
        }
        return facility;
    }
}
