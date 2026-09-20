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

import static mekhq.campaign.personnel.skills.SkillType.EXP_GREEN;
import static mekhq.campaign.personnel.skills.SkillType.S_LEADER;
import static mekhq.campaign.personnel.skills.SkillType.S_NEGOTIATION;
import static mekhq.campaign.personnel.skills.SkillType.S_TACTICS;

import megamek.client.bot.princess.BehaviorSettings;
import megamek.common.annotations.Nullable;
import mekhq.campaign.Campaign;
import mekhq.campaign.campaignOptions.CampaignOption;
import mekhq.campaign.campaignOptions.CampaignOptions;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.EnemyData;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.randomEvents.personalities.Aggression;

/**
 * Turns the contract's opposing commander into Princess surrender settings for an enemy {@link BotForce}. Loyalty sets
 * the base resolve across the whole 0-10 slider; the commander's Leadership, Tactics and Aggression, plus the player
 * commander's Negotiation, nudge it by up to {@link #MAX_TRAIT_SHIFT} steps either way.
 */
public final class OpposingForceSurrender {
    static final int NEUTRAL_RESOLVE_INDEX = 5;
    static final int MAX_TRAIT_SHIFT = 3;

    private OpposingForceSurrender() {}

    /**
     * Enables surrender on the given enemy force when the campaign allows it and the contract has an opposing
     * commander to draw resolve from. Forces without a commander (legacy or hand-edited contracts) are left alone.
     */
    public static void apply(BotForce force, @Nullable AbstractContract contract, Campaign campaign) {
        CampaignOptions options = campaign.getCampaignOptions();
        if (!options.get(CampaignOption.ENEMY_FORCES_MAY_SURRENDER)) {
            return;
        }

        EnemyData enemyData = (contract == null) ? null : contract.getEnemyData();
        Person commander = (enemyData == null) ? null : enemyData.opposingCommander();
        if (commander == null) {
            return;
        }

        int loyaltyModifier = options.get(CampaignOption.USE_LOYALTY_MODIFIERS)
              ? commander.getLoyaltyModifier(commander.getBaseLoyalty())
              : 0;
        Person playerCommander = campaign.getPlayerForce()
              .getHumanResources()
              .getCommander(options, campaign.getPlayerForce().isClanForce(), campaign.getLocalDate());

        BehaviorSettings behavior = force.getBehaviorSettings();
        behavior.setAllowSurrender(true);
        behavior.setResolveIndex(resolveIndexFor(loyaltyModifier, traitShift(commander, playerCommander)));
    }

    /**
     * Uses base rather than adjusted loyalty because {@code Person.getAdjustedLoyalty} takes the player's faction,
     * which means nothing for an enemy commander.
     *
     * @param loyaltyModifier the {@link Person#getLoyaltyModifier(int)} result, -3 (Devoted) to +3 (Treacherous)
     *
     * @return the resolve slider index before trait shifts: Devoted 10 down to Treacherous 0
     */
    static int loyaltyBase(int loyaltyModifier) {
        return switch (loyaltyModifier) {
            case -3 -> 10;
            case -2 -> 8;
            case -1 -> 7;
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 0;
            default -> NEUTRAL_RESOLVE_INDEX;
        };
    }

    static int resolveIndexFor(int loyaltyModifier, int traitShift) {
        int shift = Math.clamp(traitShift, -MAX_TRAIT_SHIFT, MAX_TRAIT_SHIFT);
        return Math.clamp(loyaltyBase(loyaltyModifier) + shift, 0, 10);
    }

    /**
     * Leadership holds the force together, Tactics recognises a lost battle sooner, and a persuasive player commander
     * talks them into yielding. Positive values mean more resolve.
     */
    static int traitShift(Person enemyCommander, @Nullable Person playerCommander) {
        return skillShift(enemyCommander, S_LEADER)
              - skillShift(enemyCommander, S_TACTICS)
              + aggressionShift(enemyCommander.getAggression())
              - skillShift(playerCommander, S_NEGOTIATION);
    }

    /**
     * @return one step per experience level above Green, so a Regular skill is worth 1 and an Elite one 3; 0 when
     *       the person or skill is missing
     */
    static int skillShift(@Nullable Person person, String skillName) {
        if (person == null) {
            return 0;
        }
        Skill skill = person.getSkill(skillName);
        if (skill == null) {
            return 0;
        }
        return Math.max(0, skill.getExperienceLevel(person.getSkillModifierData()) - EXP_GREEN);
    }

    static int aggressionShift(@Nullable Aggression aggression) {
        if (aggression == null) {
            return 0;
        }
        return switch (aggression) {
            case BLOODTHIRSTY, MURDEROUS, SAVAGE -> 2;
            case RESOLUTE, TENACIOUS, FEARLESS, STUBBORN, DETERMINED, INFLEXIBLE, COURAGEOUS, INTREPID -> 1;
            case RECKLESS -> -1;
            case PACIFISTIC, DIPLOMATIC -> -2;
            default -> 0;
        };
    }
}
