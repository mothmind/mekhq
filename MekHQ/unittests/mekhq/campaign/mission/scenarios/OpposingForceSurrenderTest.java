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

import static mekhq.campaign.personnel.skills.SkillType.EXP_ELITE;
import static mekhq.campaign.personnel.skills.SkillType.EXP_GREEN;
import static mekhq.campaign.personnel.skills.SkillType.EXP_REGULAR;
import static mekhq.campaign.personnel.skills.SkillType.EXP_VETERAN;
import static mekhq.campaign.personnel.skills.SkillType.S_LEADER;
import static mekhq.campaign.personnel.skills.SkillType.S_NEGOTIATION;
import static mekhq.campaign.personnel.skills.SkillType.S_TACTICS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import megamek.client.bot.princess.BehaviorSettings;
import mekhq.campaign.Campaign;
import mekhq.campaign.ForceHumanResources;
import mekhq.campaign.campaignOptions.CampaignOption;
import mekhq.campaign.campaignOptions.CampaignOptions;
import mekhq.campaign.force.PlayerForce;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.EnemyData;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.randomEvents.personalities.Aggression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpposingForceSurrender}: the loyalty table, each trait shift and its cap, and the wiring of the
 * campaign option and contract data into a {@link BotForce}.
 */
class OpposingForceSurrenderTest {

    private Campaign campaign;
    private CampaignOptions options;
    private ForceHumanResources humanResources;
    private AbstractContract contract;
    private EnemyData enemyData;
    private Person enemyCommander;
    private BotForce force;

    @BeforeEach
    void setUp() {
        options = mock(CampaignOptions.class);
        when(options.get(CampaignOption.ENEMY_FORCES_MAY_SURRENDER)).thenReturn(true);
        when(options.get(CampaignOption.USE_LOYALTY_MODIFIERS)).thenReturn(true);

        humanResources = mock(ForceHumanResources.class);
        PlayerForce playerForce = mock(PlayerForce.class);
        when(playerForce.getHumanResources()).thenReturn(humanResources);

        campaign = mock(Campaign.class);
        when(campaign.getCampaignOptions()).thenReturn(options);
        when(campaign.getPlayerForce()).thenReturn(playerForce);

        enemyCommander = mock(Person.class);
        when(enemyCommander.getLoyaltyModifier(any(Integer.class))).thenCallRealMethod();
        when(enemyCommander.getBaseLoyalty()).thenReturn(9);
        when(enemyCommander.getAggression()).thenReturn(Aggression.NONE);

        enemyData = mock(EnemyData.class);
        when(enemyData.opposingCommander()).thenReturn(enemyCommander);
        contract = mock(AbstractContract.class);
        when(contract.getEnemyData()).thenReturn(enemyData);

        force = new BotForce();
        force.setBehaviorSettings(new BehaviorSettings());
    }

    private static Person withSkill(Person person, String skillName, int experienceLevel) {
        Skill skill = mock(Skill.class);
        when(skill.getExperienceLevel(any())).thenReturn(experienceLevel);
        when(person.getSkill(skillName)).thenReturn(skill);
        return person;
    }

    @Test
    void loyaltyBaseSpansTheSlider() {
        assertEquals(10, OpposingForceSurrender.loyaltyBase(-3), "Devoted");
        assertEquals(8, OpposingForceSurrender.loyaltyBase(-2), "Loyal");
        assertEquals(7, OpposingForceSurrender.loyaltyBase(-1), "Reliable");
        assertEquals(5, OpposingForceSurrender.loyaltyBase(0), "Neutral");
        assertEquals(3, OpposingForceSurrender.loyaltyBase(1), "Unreliable");
        assertEquals(2, OpposingForceSurrender.loyaltyBase(2), "Disloyal");
        assertEquals(0, OpposingForceSurrender.loyaltyBase(3), "Treacherous");
    }

    @Test
    void skillShiftIsOneStepPerLevelAboveGreen() {
        assertEquals(0, OpposingForceSurrender.skillShift(null, S_LEADER), "no person");
        assertEquals(0, OpposingForceSurrender.skillShift(mock(Person.class), S_LEADER), "no skill");
        assertEquals(0, OpposingForceSurrender.skillShift(withSkill(mock(Person.class), S_LEADER, EXP_GREEN),
              S_LEADER));
        assertEquals(1, OpposingForceSurrender.skillShift(withSkill(mock(Person.class), S_LEADER, EXP_REGULAR),
              S_LEADER));
        assertEquals(3, OpposingForceSurrender.skillShift(withSkill(mock(Person.class), S_LEADER, EXP_ELITE),
              S_LEADER));
    }

    @Test
    void aggressionShiftTable() {
        assertEquals(0, OpposingForceSurrender.aggressionShift(null));
        assertEquals(0, OpposingForceSurrender.aggressionShift(Aggression.NONE));
        assertEquals(0, OpposingForceSurrender.aggressionShift(Aggression.HOT_HEADED));
        assertEquals(1, OpposingForceSurrender.aggressionShift(Aggression.RESOLUTE));
        assertEquals(2, OpposingForceSurrender.aggressionShift(Aggression.BLOODTHIRSTY));
        assertEquals(-1, OpposingForceSurrender.aggressionShift(Aggression.RECKLESS));
        assertEquals(-2, OpposingForceSurrender.aggressionShift(Aggression.PACIFISTIC));
    }

    @Test
    void traitShiftCombinesAndTheIndexCapsIt() {
        withSkill(enemyCommander, S_LEADER, EXP_ELITE);
        when(enemyCommander.getAggression()).thenReturn(Aggression.BLOODTHIRSTY);
        assertEquals(5, OpposingForceSurrender.traitShift(enemyCommander, null), "Elite Leadership + Bloodthirsty");
        assertEquals(8, OpposingForceSurrender.resolveIndexFor(0, 5), "capped to +3 over Neutral");
        assertEquals(10, OpposingForceSurrender.resolveIndexFor(-3, 5), "and clamped to the slider");

        Person negotiator = withSkill(mock(Person.class), S_NEGOTIATION, EXP_VETERAN);
        withSkill(enemyCommander, S_TACTICS, EXP_ELITE);
        assertEquals(0, OpposingForceSurrender.traitShift(enemyCommander, negotiator),
              "+3 Leadership +2 Bloodthirsty -3 Tactics -2 Negotiation");
        assertEquals(0, OpposingForceSurrender.resolveIndexFor(3, -1), "Treacherous cannot go below 0");
    }

    @Test
    void applySetsSurrenderFromTheOpposingCommander() {
        when(enemyCommander.getBaseLoyalty()).thenReturn(16);
        withSkill(enemyCommander, S_TACTICS, EXP_REGULAR);

        OpposingForceSurrender.apply(force, contract, campaign);

        assertTrue(force.getBehaviorSettings().isAllowSurrender());
        assertEquals(6, force.getBehaviorSettings().getResolveIndex(), "Loyal 8, minus 1 for Regular Tactics");
    }

    @Test
    void applyUsesThePlayerCommandersNegotiation() {
        Person playerCommander = withSkill(mock(Person.class), S_NEGOTIATION, EXP_ELITE);
        when(humanResources.getCommander(any(), any(Boolean.class), any())).thenReturn(playerCommander);

        OpposingForceSurrender.apply(force, contract, campaign);

        assertEquals(2, force.getBehaviorSettings().getResolveIndex(), "Neutral 5, minus 3 for Elite Negotiation");
    }

    @Test
    void applyIgnoresLoyaltyWhenLoyaltyModifiersAreOff() {
        when(options.get(CampaignOption.USE_LOYALTY_MODIFIERS)).thenReturn(false);
        when(enemyCommander.getBaseLoyalty()).thenReturn(3);

        OpposingForceSurrender.apply(force, contract, campaign);

        assertTrue(force.getBehaviorSettings().isAllowSurrender());
        assertEquals(5, force.getBehaviorSettings().getResolveIndex());
    }

    @Test
    void applyDoesNothingWhenTheOptionIsOff() {
        when(options.get(CampaignOption.ENEMY_FORCES_MAY_SURRENDER)).thenReturn(false);

        OpposingForceSurrender.apply(force, contract, campaign);

        assertFalse(force.getBehaviorSettings().isAllowSurrender());
        assertEquals(5, force.getBehaviorSettings().getResolveIndex());
    }

    @Test
    void applyDoesNothingWithoutAnOpposingCommander() {
        OpposingForceSurrender.apply(force, null, campaign);
        assertFalse(force.getBehaviorSettings().isAllowSurrender(), "no contract");

        when(contract.getEnemyData()).thenReturn(null);
        OpposingForceSurrender.apply(force, contract, campaign);
        assertFalse(force.getBehaviorSettings().isAllowSurrender(), "no enemy data");

        when(contract.getEnemyData()).thenReturn(enemyData);
        when(enemyData.opposingCommander()).thenReturn(null);
        OpposingForceSurrender.apply(force, contract, campaign);
        assertFalse(force.getBehaviorSettings().isAllowSurrender(), "no commander");
    }
}
