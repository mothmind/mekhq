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
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import megamek.common.Player;
import megamek.common.annotations.Nullable;
import megamek.common.game.Game;
import megamek.common.options.IOption;
import megamek.common.options.PilotOptions;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import mekhq.Utilities;
import mekhq.campaign.Campaign;
import mekhq.campaign.personnel.Award;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.PersonAwardController;
import mekhq.campaign.personnel.enums.GenderDescriptors;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.personnel.skills.Skills;
import mekhq.campaign.unit.Unit;

/**
 * Who is speaking: the name they go by on comms, and what the model knows about them. The campaign's own pilots get
 * their full record; everyone else gets a sketch drawn from their unit, and is told to invent a persona and keep to
 * it.
 *
 * @param key          identifies the speaker in the journal across battles for campaign pilots, and within the battle
 *                     for everyone else
 * @param callName     the name shown in chat
 * @param sheet        what the model is told about them
 * @param campaignPilot whether this is one of the campaign's own people
 * @param pronouns     how others should refer to them, such as "she/her"; blank when unknown
 */
public record PilotDossier(String key, String callName, String sheet, boolean campaignPilot, String pronouns) {
    static final int MAX_BIOGRAPHY = 400;
    static final int MAX_CHARACTER = 600;
    static final int MAX_ABILITY = 160;
    static final int MAX_DECORATIONS = 8;
    private static final PilotOptions RULES = new PilotOptions();

    /**
     * @param entity       the speaker's unit
     * @param campaign     the campaign
     * @param battleKey    identifies this battle, so non-campaign speakers keep continuity only within it
     * @param enemyFaction the contract's enemy, for enemy pilots; may be {@code null}
     * @param campaignSide the player fielding the campaign's units, to tell allies from enemies; may be {@code null}
     */
    public static PilotDossier forEntity(Entity entity, Campaign campaign, String battleKey,
          @Nullable String enemyFaction, @Nullable Player campaignSide) {
        Person person = campaignPilot(entity, campaign);
        return (person != null) ?
                     forCampaignPilot(entity, person, campaign) :
                     forOtherPilot(entity, battleKey, enemyFaction, campaignSide);
    }

    /**
     * @return the player fielding the campaign's own units, or {@code null} if none are in the game
     */
    public static @Nullable Player campaignSide(Game game, Campaign campaign) {
        for (Entity entity : game.getEntitiesVector()) {
            if ((entity.getOwner() != null) && (campaignUnit(entity, campaign) != null)) {
                return entity.getOwner();
            }
        }
        return null;
    }

    private static @Nullable Unit campaignUnit(Entity entity, Campaign campaign) {
        UUID unitId;
        try {
            unitId = UUID.fromString(entity.getExternalIdAsString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return campaign.getUnit(unitId);
    }

    /**
     * @return how a campaign pilot's comrades know them, such as "Bomber (O-Bakemono OBK-M10, she/her)", or
     *       {@code null} if the unit is not one of the campaign's
     */
    public static @Nullable String comrade(Entity entity, Campaign campaign) {
        Person person = campaignPilot(entity, campaign);
        if (person == null) {
            return null;
        }
        String callsign = person.getCallsign();
        String name = ((callsign != null) && !callsign.isBlank()) ? callsign.strip() : person.getFullName();
        String pronouns = pronouns(person);
        return name + " (" + entity.getShortName() + (pronouns.isEmpty() ? "" : ", " + pronouns) + ")";
    }

    /**
     * @return whether a unit's owner fights against the campaign's side; without a campaign side, anyone off team 1
     */
    public static boolean isEnemy(@Nullable Player owner, @Nullable Player campaignSide) {
        if (owner == null) {
            return true;
        }
        return (campaignSide == null) ? (owner.getTeam() != 1) : campaignSide.isEnemyOf(owner);
    }

    private static @Nullable Person campaignPilot(Entity entity, Campaign campaign) {
        Unit unit = campaignUnit(entity, campaign);
        return (unit == null) ? null : unit.getCommander();
    }

    static PilotDossier forCampaignPilot(Entity entity, Person person, Campaign campaign) {
        String callsign = person.getCallsign();
        boolean hasCallsign = (callsign != null) && !callsign.isBlank();
        String callName = (hasCallsign ? callsign.strip() : person.getFullName()) + " (" + entity.getShortName() + ")";

        List<String> sheet = new ArrayList<>();
        sheet.add("Name: " + person.getFullTitle() + (hasCallsign ? ", callsign \"" + callsign.strip() + "\"" : ""));
        String pronouns = pronouns(person);
        if (!pronouns.isEmpty()) {
            sheet.add("Pronouns: " + pronouns);
        }
        sheet.add("Side: one of the player's own MekWarriors, in " + campaign.getPlayerForce().getName());
        if (person.getOriginFaction() != null) {
            sheet.add("From: " + person.getOriginFaction().getFullName(campaign.getGameYear()));
        }
        String personality = personality(person);
        if (!personality.isEmpty()) {
            sheet.add("Personality: " + personality);
        }
        String character = plain(person.getPersonalityDescription());
        if (!character.isEmpty()) {
            sheet.add("Character: " + abbreviate(character, MAX_CHARACTER));
        }
        String biography = plain(person.getBiography());
        if (!biography.isEmpty()) {
            sheet.add("Background: " + abbreviate(biography, MAX_BIOGRAPHY));
        }
        String abilities = abilities(person);
        if (!abilities.isEmpty()) {
            sheet.add("Special abilities:\n" + abilities);
        }
        String skills = otherSkills(person);
        if (!skills.isEmpty()) {
            sheet.add("Other skills: " + skills);
        }
        String decorations = decorations(person);
        if (!decorations.isEmpty()) {
            sheet.add("Decorations: " + decorations);
        }
        sheet.add("Kills so far: " + campaign.getKillsFor(person.getId()).size());
        if (person.getHits() > 0) {
            sheet.add("Carrying " + person.getHits() + " wound(s) from earlier fighting");
        }
        sheet.add(unitLine(entity));
        return new PilotDossier("person:" + person.getId(), callName, String.join("\n", sheet), true, pronouns);
    }

    private static String pronouns(Person person) {
        if (person.getGender() == null) {
            return "";
        }
        return GenderDescriptors.HE_SHE_THEY.getDescriptor(person.getGender()).strip() + "/"
                     + GenderDescriptors.HIM_HER_THEM.getDescriptor(person.getGender()).strip();
    }

    static PilotDossier forOtherPilot(Entity entity, String battleKey, @Nullable String enemyFaction,
          @Nullable Player campaignSide) {
        Crew crew = entity.getCrew();
        String name = ((crew != null) && (crew.getName() != null) && !crew.getName().isBlank()) ?
                            crew.getName() :
                            "the pilot";
        String side = isEnemy(entity.getOwner(), campaignSide) ?
                            "an enemy pilot" + (((enemyFaction != null) && !enemyFaction.isBlank()) ?
                                                      ", fighting for " + enemyFaction :
                                                      "") :
                            "an allied pilot fighting alongside the player's MekWarriors";

        List<String> sheet = new ArrayList<>();
        sheet.add("Name: " + name);
        sheet.add("Side: " + side);
        sheet.add(unitLine(entity));
        sheet.add("There is no personal file on this pilot: invent a fitting persona and keep to it.");
        return new PilotDossier("battle:" + battleKey + ":" + entity.getId(), name + " (" + entity.getShortName() + ")",
              String.join("\n", sheet), false, "");
    }

    private static String unitLine(Entity entity) {
        Crew crew = entity.getCrew();
        String skills = (crew == null) ? "" : ", gunnery " + crew.getGunnery() + " / piloting " + crew.getPiloting();
        return "Piloting: " + entity.getShortName() + skills;
    }

    /**
     * @return one line per special ability, naming it and saying what it does in a sentence
     */
    private static String abilities(Person person) {
        Enumeration<IOption> options = person.getOptions(PilotOptions.LVL3_ADVANTAGES);
        if (options == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        while (options.hasMoreElements()) {
            IOption option = options.nextElement();
            if (!option.booleanValue()) {
                continue;
            }
            String description = abilityDescription(option);
            lines.add("- " + plain(Utilities.getOptionDisplayName(option))
                            + (description.isEmpty() ? "" : ": " + description));
        }
        return String.join("\n", lines);
    }

    /**
     * @return the first sentence of what the ability does: MegaMek's rules text if it has any, MekHQ's otherwise
     */
    static String abilityDescription(IOption option) {
        IOption rules = RULES.getOption(option.getName());
        String description = (rules == null) ? "" : plain(rules.getDescription());
        if (description.isEmpty()) {
            description = plain(option.getDescription());
        }
        if (description.equals(option.getName())) {
            return "";
        }
        int end = description.indexOf(". ");
        return abbreviate((end < 0) ? description : description.substring(0, end + 1), MAX_ABILITY);
    }

    /**
     * @return every skill but gunnery and piloting, which the unit line already gives, with how good they are at it
     */
    private static String otherSkills(Person person) {
        Skills skills = person.getSkills();
        if (skills == null) {
            return "";
        }
        List<String> named = new ArrayList<>();
        for (String name : skills.getSkillNames()) {
            Skill skill = skills.getSkill(name);
            if ((skill == null) || name.startsWith("Gunnery") || name.startsWith("Piloting")) {
                continue;
            }
            named.add(name + " (" + skill.getSkillLevel(person.getSkillModifierData()) + ")");
        }
        return String.join(", ", named);
    }

    /**
     * @return the pilot's awards, most awarded first, with how many times each was won
     */
    private static String decorations(Person person) {
        PersonAwardController controller = person.getAwardController();
        if ((controller == null) || (controller.getAwards() == null)) {
            return "";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Award award : controller.getAwards()) {
            counts.merge(award.getName(), Math.max(1, award.getQuantity()), Integer::sum);
        }
        return String.join(", ", counts.entrySet().stream()
                                       .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                       .limit(MAX_DECORATIONS)
                                       .map(entry -> (entry.getValue() > 1) ?
                                                           entry.getKey() + " \u00d7" + entry.getValue() :
                                                           entry.getKey())
                                       .toList());
    }

    private static String personality(Person person) {
        List<String> traits = new ArrayList<>();
        trait(traits, "aggression", person.getAggression());
        trait(traits, "ambition", person.getAmbition());
        trait(traits, "greed", person.getGreed());
        trait(traits, "social", person.getSocial());
        trait(traits, "reasoning", person.getReasoning());
        trait(traits, "quirk", person.getPersonalityQuirk());
        return String.join(", ", traits);
    }

    private static void trait(List<String> traits, String name, @Nullable Enum<?> value) {
        if ((value == null) || "NONE".equals(value.name())) {
            return;
        }
        String label = value.toString();
        if ((label != null) && !label.isBlank()) {
            traits.add(name + " " + label.strip());
        }
    }

    static String plain(@Nullable String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?i)<br\\s*/?>", ", ").replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
                     .replaceAll("(,\\s*)+$", "").strip();
    }

    static String abbreviate(String text, int max) {
        return (text.length() <= max) ? text : text.substring(0, max - 1).strip() + "…";
    }
}
