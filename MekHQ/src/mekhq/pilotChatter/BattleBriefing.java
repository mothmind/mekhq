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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import megamek.common.annotations.Nullable;
import mekhq.campaign.Campaign;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioObjective;

/**
 * What a pilot knows beyond their own unit. The campaign's own pilots know the company's story, the mission and who
 * else from the company is in the fight; everyone else knows only the company's public face.
 *
 * @param lore      the company's lore
 * @param forceName the campaign's force, named to outsiders when the lore gives no public face
 * @param mission   the contract and this battle's objectives; empty if there are none
 */
public record BattleBriefing(CompanyLore lore, String forceName, String mission) {
    static final BattleBriefing NONE = new BattleBriefing(CompanyLore.NONE, "", "");
    static final int MAX_OBJECTIVES = 3;
    static final int MAX_COMRADES = 12;
    static final List<String> RECALLED = List.of("where the company comes from", "what the company has lost",
          "the cover the company lives under", "what the company has been through lately");

    /**
     * @return the contract and battle the scenario belongs to, as the campaign's own pilots would be briefed on them
     */
    public static String mission(Campaign campaign, Scenario scenario) {
        List<String> lines = new ArrayList<>();
        AbstractContract contract = campaign.getContract(scenario.getMissionId());
        if (contract != null) {
            String employer = contract.getEmployerDisplayName();
            String enemy = contract.getEnemyDisplayName();
            boolean sides = (employer != null) && !employer.isBlank() && (enemy != null) && !enemy.isBlank();
            lines.add("Contract: " + contract.getName() + (sides ? ", for " + employer + " against " + enemy : "")
                            + ".");
        }

        StringBuilder battle = new StringBuilder("This battle: ").append(scenario.getName()).append('.');
        List<ScenarioObjective> objectives = scenario.getScenarioObjectives();
        if (objectives != null) {
            List<String> described = objectives.stream()
                                           .filter(Objects::nonNull)
                                           .map(objective -> PilotDossier.plain(objective.getDescription()))
                                           .filter(description -> !description.isEmpty())
                                           .limit(MAX_OBJECTIVES)
                                           .toList();
            if (!described.isEmpty()) {
                battle.append(" Objectives: ").append(String.join("; ", described)).append('.');
            }
        }
        lines.add(battle.toString());
        return String.join("\n", lines);
    }

    /**
     * @param comrades the company's other pilots in this battle, as {@link PilotDossier#comrade} names them
     *
     * @return the briefing for one of the campaign's own pilots; empty if there is nothing to tell
     */
    String forCampaignPilot(List<String> comrades) {
        StringBuilder briefing = new StringBuilder();
        section(briefing, "THE COMPANY", lore.story());
        section(briefing, "THE MISSION", mission);
        section(briefing, "THE PILOT'S COMRADES IN THIS BATTLE",
              String.join("\n", comrades.stream().limit(MAX_COMRADES).map(comrade -> "- " + comrade).toList()));
        return briefing.toString();
    }

    /**
     * Asks for a line that reaches back into the company's story. Left to themselves the models never do: the
     * battle always wins. Pointing at one side of the story at a time keeps them off the same detail every time.
     *
     * @param aspect which side of the story to point at, any number
     *
     * @return the request, or empty if the lore tells no story
     */
    String recall(int aspect) {
        if (lore.story().isEmpty()) {
            return "";
        }
        return "Something about this moment takes the pilot back to " + RECALLED.get(Math.floorMod(aspect,
              RECALLED.size())) + ": bring one detail of it from THE COMPANY into the line.";
    }

    /**
     * @return the briefing for anyone else in the battle, friend or foe; empty if there is nothing to tell
     */
    String forOtherPilot(boolean enemy) {
        StringBuilder briefing = new StringBuilder();
        String company = lore.publicFace().isEmpty() ? forceName : lore.publicFace();
        section(briefing, enemy ? "WHO THEY ARE FIGHTING" : "WHO THEY FIGHT ALONGSIDE", company);
        return briefing.toString();
    }

    private static void section(StringBuilder briefing, String heading, @Nullable String body) {
        if ((body != null) && !body.isBlank()) {
            briefing.append(heading).append('\n').append(body.strip()).append("\n\n");
        }
    }
}
