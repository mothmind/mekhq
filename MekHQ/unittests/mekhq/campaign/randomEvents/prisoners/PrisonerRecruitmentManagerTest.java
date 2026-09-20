/*
 * Copyright (C) 2025-2026 The MegaMek Team. All Rights Reserved.
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
package mekhq.campaign.randomEvents.prisoners;

import static mekhq.campaign.personnel.skills.SkillType.S_NEGOTIATION;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.MAX_MINUTES_PER_PRISONER_PER_DAY;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.allocateDailyMinutes;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.calculateCaptivityModifier;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.calculateLoyaltyModifier;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.calculateModifiers;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.calculateTimeModifier;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.cleanupInvalidAssignments;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.determineOutcome;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.isImpossible;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentManager.processDailyWork;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentOutcome.FAILURE;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentOutcome.FUMBLE;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentOutcome.HARDENED;
import static mekhq.campaign.randomEvents.prisoners.PrisonerRecruitmentOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static testUtilities.MHQTestUtilities.mockCampaign;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import megamek.Version;
import megamek.common.TargetRollModifier;
import megamek.common.compute.Compute;
import mekhq.campaign.Campaign;
import mekhq.campaign.ForceHumanResources;
import mekhq.campaign.campaignOptions.CampaignOptions;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.enums.PersonnelStatus;
import mekhq.campaign.personnel.ranks.Ranks;
import mekhq.campaign.personnel.skills.SkillType;
import mekhq.campaign.universe.Faction;
import mekhq.campaign.universe.Factions;
import mekhq.utilities.MHQXMLUtility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;

class PrisonerRecruitmentManagerTest {
    private static final Version VERSION = new Version("0.51.01");
    private static final LocalDate TODAY = LocalDate.of(3151, 6, 2);

    private Campaign campaign;
    private ForceHumanResources humanResources;
    private final Map<UUID, Person> roster = new HashMap<>();

    @BeforeAll
    static void beforeAll() {
        SkillType.initializeTypes();
        Ranks.initializeRankSystems();
        Factions.setInstance(Factions.loadDefault(true));
    }

    @BeforeEach
    void beforeEach() {
        campaign = mockCampaign();
        humanResources = mock(ForceHumanResources.class);
        when(campaign.getPlayerForce().getHumanResources()).thenReturn(humanResources);
        when(campaign.getLocalDate()).thenReturn(TODAY);
        when(campaign.getVersion()).thenReturn(VERSION);
        when(campaign.getCampaignOptions()).thenReturn(new CampaignOptions());

        roster.clear();
        lenient().when(humanResources.getPerson(any())).thenAnswer(invocation -> roster.get(invocation.getArgument(0)));
        lenient().when(humanResources.getPersonnel()).thenAnswer(invocation -> new ArrayList<>(roster.values()));
        lenient().when(humanResources.getActivePersonnel(false, false))
              .thenAnswer(invocation -> roster.values()
                                              .stream()
                                              .filter(person -> person.getStatus().isActive() &&
                                                                      person.getPrisonerStatus().isFreeOrBondsman())
                                              .toList());
        lenient().when(humanResources.getCurrentPrisoners())
              .thenAnswer(invocation -> roster.values()
                                              .stream()
                                              .filter(person -> person.getPrisonerStatus().isCurrentPrisoner())
                                              .toList());
    }

    private Person recruiter(int negotiationLevel) {
        Person person = new Person("", "Nego", "Tiator", "", campaign, "MERC");
        person.addSkill(S_NEGOTIATION, negotiationLevel, 0);
        roster.put(person.getId(), person);
        return person;
    }

    private Person prisoner() {
        Person person = new Person("", "Pri", "Soner", "", campaign, "MERC");
        person.setPrisonerStatusDirect(PrisonerStatus.PRISONER);
        roster.put(person.getId(), person);
        return person;
    }

    // region Modifiers

    @ParameterizedTest
    @CsvSource({ "1,-2", "6,-2", "7,0", "9,0", "10,2", "12,2", "13,5", "14,5", "15,8", "16,8", "17,12", "18,12",
                 "25,12" })
    void loyaltyBands(int loyalty, int expected) {
        assertEquals(expected, calculateLoyaltyModifier(loyalty));
    }

    @ParameterizedTest
    @CsvSource({ "0,0", "359,0", "360,-1", "719,-1", "1439,-3", "1440,-4", "1680,-4", "-10,0" })
    void timeSteps(int minutes, int expected) {
        assertEquals(expected, calculateTimeModifier(minutes));
    }

    @ParameterizedTest
    @CsvSource({ "0,0", "2,0", "3,-1", "5,-1", "6,-2", "12,-2" })
    void captivitySteps(int monthsHeld, int expected) {
        assertEquals(expected, calculateCaptivityModifier(TODAY.minusMonths(monthsHeld), TODAY));
    }

    @Test
    void captivityUnknownOrFutureIsZero() {
        assertEquals(0, calculateCaptivityModifier(null, TODAY));
        assertEquals(0, calculateCaptivityModifier(TODAY.plusDays(1), TODAY));
    }

    @Test
    void impossibleBoundary() {
        assertFalse(isImpossible(12));
        assertTrue(isImpossible(13));
    }

    @Test
    void fullModifierSetForNeutralOutsiderWithNoTime() {
        Person prisoner = prisoner();
        prisoner.setLoyalty(8);
        prisoner.setCaptureDate(TODAY.minusDays(3));
        Faction campaignFaction = mock(Faction.class);

        List<TargetRollModifier> modifiers = calculateModifiers(prisoner, campaignFaction, false, 0, TODAY, true);

        // base resistance, mercenary origin (test persons are MERC); nothing else applies
        assertEquals(PrisonerRecruitmentManager.BASE_RESISTANCE_MODIFIER +
                           PrisonerRecruitmentManager.MERCENARY_MODIFIER, sum(modifiers));
    }

    @Test
    void fullModifierSetForDevotedLongHeldSameFaction() {
        Person prisoner = prisoner();
        prisoner.setLoyalty(15);
        prisoner.setCaptureDate(TODAY.minusMonths(7));

        List<TargetRollModifier> modifiers = calculateModifiers(prisoner, prisoner.getOriginFaction(), false,
              1500, TODAY, false);

        int expected = PrisonerRecruitmentManager.BASE_RESISTANCE_MODIFIER + 8 - 4 +
                             PrisonerRecruitmentManager.SAME_FACTION_MODIFIER +
                             PrisonerRecruitmentManager.MERCENARY_MODIFIER - 2;
        assertEquals(expected, sum(modifiers));
        assertTrue(modifiers.stream().noneMatch(modifier -> modifier.getDesc().contains("15")),
              "loyalty score must stay hidden when asked");
    }

    private static int sum(List<TargetRollModifier> modifiers) {
        return modifiers.stream().mapToInt(TargetRollModifier::value).sum();
    }

    // endregion Modifiers

    // region Outcomes

    @ParameterizedTest
    @CsvSource({ "10,SUCCESS", "3,SUCCESS", "0,SUCCESS", "-1,FAILURE", "-3,FAILURE", "-4,HARDENED", "-5,HARDENED",
                 "-6,FUMBLE", "-10,FUMBLE" })
    void outcomeByMargin(int margin, PrisonerRecruitmentOutcome expected) {
        assertEquals(expected, determineOutcome(margin));
    }

    @Test
    void outcomeConstantsAgree() {
        assertEquals(SUCCESS, determineOutcome(0));
        assertEquals(FAILURE, determineOutcome(PrisonerRecruitmentManager.HARDENED_MARGIN + 1));
        assertEquals(HARDENED, determineOutcome(PrisonerRecruitmentManager.HARDENED_MARGIN));
        assertEquals(FUMBLE, determineOutcome(PrisonerRecruitmentManager.FUMBLE_MARGIN));
    }

    // endregion Outcomes

    // region Daily work

    @Test
    void dailyAllocationFillsFromTheFront() {
        assertArrayEquals(new int[0], allocateDailyMinutes(0));
        assertArrayEquals(new int[] { 240 }, allocateDailyMinutes(1));
        assertArrayEquals(new int[] { 240, 240 }, allocateDailyMinutes(2));
        assertArrayEquals(new int[] { 240, 240, 0 }, allocateDailyMinutes(3));
        assertArrayEquals(new int[] { 240, 240, 0, 0, 0 }, allocateDailyMinutes(5));
    }

    @Test
    void dailyWorkFollowsQueueAndSkipsBlocked() {
        Person recruiter = recruiter(4);
        Person first = prisoner();
        Person second = prisoner();
        Person third = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, first));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, second));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, third));

        processDailyWork(campaign);
        assertEquals(MAX_MINUTES_PER_PRISONER_PER_DAY, first.getRecruitmentMinutesThisWeek());
        assertEquals(MAX_MINUTES_PER_PRISONER_PER_DAY, second.getRecruitmentMinutesThisWeek());
        assertEquals(0, third.getRecruitmentMinutesThisWeek());

        // A refusing prisoner is skipped and the slot passes down the queue
        second.setRecruitmentBlockedUntil(TODAY.plusDays(10));
        processDailyWork(campaign);
        assertEquals(2 * MAX_MINUTES_PER_PRISONER_PER_DAY, first.getRecruitmentMinutesThisWeek());
        assertEquals(MAX_MINUTES_PER_PRISONER_PER_DAY, second.getRecruitmentMinutesThisWeek());
        assertEquals(MAX_MINUTES_PER_PRISONER_PER_DAY, third.getRecruitmentMinutesThisWeek());

        // Moving to the top changes who gets the time
        PrisonerRecruitmentManager.moveToTop(campaign, third);
        assertEquals(third.getId(), recruiter.getRecruitmentAssignments().getFirst());
    }

    // endregion Daily work

    // region Assignments

    @Test
    void capIsSkillLevelPlusOne() {
        assertEquals(1, PrisonerRecruitmentManager.getMaxAssignments(recruiter(0)));
        assertEquals(4, PrisonerRecruitmentManager.getMaxAssignments(recruiter(3)));
        assertEquals(8, PrisonerRecruitmentManager.getMaxAssignments(recruiter(7)));
    }

    @Test
    void assignRespectsCapAndMovesBetweenRecruiters() {
        Person green = recruiter(1);
        Person veteran = recruiter(4);
        Person a = prisoner();
        Person b = prisoner();
        Person c = prisoner();

        assertTrue(PrisonerRecruitmentManager.assign(campaign, green, a));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, green, b));
        assertFalse(PrisonerRecruitmentManager.assign(campaign, green, c), "third prisoner exceeds the cap of 2");
        assertNull(c.getRecruiterId());
        assertEquals(2, PrisonerRecruitmentManager.getCurrentLoad(green));

        // Re-assigning to the same recruiter is a harmless no-op
        assertTrue(PrisonerRecruitmentManager.assign(campaign, green, a));
        assertEquals(2, PrisonerRecruitmentManager.getCurrentLoad(green));

        // Moving to another recruiter clears the old link
        assertTrue(PrisonerRecruitmentManager.assign(campaign, veteran, a));
        assertEquals(veteran.getId(), a.getRecruiterId());
        assertFalse(green.getRecruitmentAssignments().contains(a.getId()));
        assertTrue(veteran.getRecruitmentAssignments().contains(a.getId()));
    }

    @Test
    void cannotAssignBlockedOrFreePeople() {
        Person recruiter = recruiter(3);
        Person blocked = prisoner();
        blocked.setRecruitmentBlockedUntil(TODAY);
        assertFalse(PrisonerRecruitmentManager.assign(campaign, recruiter, blocked));

        Person free = new Person("", "Fr", "Ee", "", campaign, "MERC");
        roster.put(free.getId(), free);
        assertFalse(PrisonerRecruitmentManager.assign(campaign, recruiter, free));

        Person noSkill = new Person("", "No", "Skill", "", campaign, "MERC");
        roster.put(noSkill.getId(), noSkill);
        assertFalse(PrisonerRecruitmentManager.assign(campaign, noSkill, prisoner()));

        Person willing = prisoner();
        willing.setPrisonerStatusDirect(PrisonerStatus.PRISONER_DEFECTOR);
        assertFalse(PrisonerRecruitmentManager.assign(campaign, recruiter, willing), "already willing to defect");
    }

    @Test
    void cleanupDropsDanglingAndInvalidLinks() {
        Person recruiter = recruiter(3);
        Person kept = prisoner();
        Person freed = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, kept));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, freed));

        // A prisoner pointing at a recruiter who no longer exists
        Person orphan = prisoner();
        orphan.setRecruiterId(UUID.randomUUID());
        // A recruiter holding an id nobody has
        recruiter.addRecruitmentAssignment(UUID.randomUUID());
        // A prisoner freed behind the manager's back
        freed.setPrisonerStatusDirect(PrisonerStatus.FREE);
        // An expired refusal
        kept.setRecruitmentBlockedUntil(TODAY.minusDays(1));

        cleanupInvalidAssignments(campaign);

        assertNull(orphan.getRecruiterId());
        assertNull(freed.getRecruiterId());
        assertNull(kept.getRecruitmentBlockedUntil());
        assertEquals(List.of(kept.getId()), recruiter.getRecruitmentAssignments());
        assertEquals(recruiter.getId(), kept.getRecruiterId());
    }

    @Test
    void cleanupUnlinksRecruiterWhoLostEligibility() {
        Person recruiter = recruiter(3);
        Person prisoner = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, prisoner));

        recruiter.setStatus(PersonnelStatus.ON_LEAVE);
        cleanupInvalidAssignments(campaign);

        assertNull(prisoner.getRecruiterId());
        assertTrue(recruiter.getRecruitmentAssignments().isEmpty());
    }

    @Test
    void cleanupTrimsOverCapFromTheBack() {
        Person recruiter = recruiter(2);
        Person first = prisoner();
        Person second = prisoner();
        Person third = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, first));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, second));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, third));

        // Skill lowered after the fact
        recruiter.addSkill(S_NEGOTIATION, 0, 0);
        cleanupInvalidAssignments(campaign);

        assertEquals(List.of(first.getId()), recruiter.getRecruitmentAssignments());
        assertNull(second.getRecruiterId());
        assertNull(third.getRecruiterId());
    }

    @Test
    void prisonerStatusChangeClearsLinks() {
        Person recruiter = recruiter(3);
        Person prisoner = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, prisoner));

        prisoner.setPrisonerStatus(campaign, PrisonerStatus.FREE, false);

        assertNull(prisoner.getRecruiterId());
        assertTrue(recruiter.getRecruitmentAssignments().isEmpty());
    }

    @Test
    void captureDateIsSetOnFirstCaptureOnly() {
        Person person = new Person("", "Cap", "Tive", "", campaign, "MERC");
        assertNull(person.getCaptureDate());

        person.setPrisonerStatus(campaign, PrisonerStatus.PRISONER, false);
        assertEquals(TODAY, person.getCaptureDate());

        when(campaign.getLocalDate()).thenReturn(TODAY.plusDays(30));
        person.setPrisonerStatus(campaign, PrisonerStatus.PRISONER_DEFECTOR, false);
        assertEquals(TODAY, person.getCaptureDate(), "defector transition must not reset the capture date");
    }

    // endregion Assignments

    // region Injury and persistence

    @Test
    void injuryNeverExceedsFiveHits() {
        Person recruiter = recruiter(3);
        recruiter.setHits(5);
        PrisonerRecruitmentManager.injureRecruiter(campaign, recruiter, 1);
        assertEquals(5, recruiter.getHits());

        recruiter.setHits(2);
        PrisonerRecruitmentManager.injureRecruiter(campaign, recruiter, 1);
        assertEquals(3, recruiter.getHits());
    }

    @Test
    void fieldsSurviveSaveAndLoad() throws Exception {
        Person prisoner = prisoner();
        UUID recruiterId = UUID.randomUUID();
        prisoner.setRecruiterId(recruiterId);
        prisoner.setRecruitmentMinutesThisWeek(300);
        prisoner.setRecruitmentBlockedUntil(TODAY.plusDays(5));
        prisoner.setCaptureDate(TODAY.minusDays(40));

        Person loaded = roundTrip(prisoner);
        assertEquals(recruiterId, loaded.getRecruiterId());
        assertEquals(300, loaded.getRecruitmentMinutesThisWeek());
        assertEquals(TODAY.plusDays(5), loaded.getRecruitmentBlockedUntil());
        assertEquals(TODAY.minusDays(40), loaded.getCaptureDate());

        Person recruiter = recruiter(3);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        recruiter.addRecruitmentAssignment(first);
        recruiter.addRecruitmentAssignment(second);

        Person loadedRecruiter = roundTrip(recruiter);
        assertEquals(List.of(first, second), loadedRecruiter.getRecruitmentAssignments(), "queue order must survive");
    }

    private Person roundTrip(Person person) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(bytes, true, StandardCharsets.UTF_8)) {
            person.writeToXML(writer, 0, campaign);
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
            Element element = MHQXMLUtility.newSafeDocumentBuilder().parse(input).getDocumentElement();
            element.normalize();
            Person loaded = Person.generateInstanceFromXML(element, campaign, VERSION);
            assertNotNull(loaded);
            return loaded;
        }
    }

    // endregion Injury and persistence

    // region Weekly roll

    @Test
    void weeklyRollHardensOnBadFailure() {
        Person recruiter = recruiter(3);
        Person prisoner = prisoner();
        prisoner.setLoyalty(8);
        prisoner.setCaptureDate(TODAY.minusDays(3));
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, prisoner));
        prisoner.setRecruitmentMinutesThisWeek(1440);
        int loyaltyBefore = prisoner.getBaseLoyalty();

        // TN = 7 + 4 (base) - 4 (time) - 1 (merc) = 6; a roll of 2 is MoS -4 → hardened, not fumbled
        try (MockedStatic<Compute> compute = mockStatic(Compute.class)) {
            compute.when(Compute::d6).thenReturn(1, 1);
            PrisonerRecruitmentManager.processWeeklyRolls(campaign);
        }

        assertEquals(loyaltyBefore + PrisonerRecruitmentManager.HARDENING_LOYALTY_CHANGE, prisoner.getBaseLoyalty());
        assertNull(prisoner.getRecruiterId());
        assertTrue(recruiter.getRecruitmentAssignments().isEmpty());
        assertEquals(TODAY.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()),
              prisoner.getRecruitmentBlockedUntil());
        assertTrue(prisoner.getPrisonerStatus().isPrisoner(), "hardened prisoners stay prisoners");
    }

    @Test
    void weeklyRollIsSkippedWhenImpossible() {
        Person recruiter = recruiter(0);
        Person prisoner = prisoner();
        prisoner.setLoyalty(18);
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, prisoner));
        prisoner.setRecruitmentMinutesThisWeek(1440);

        try (MockedStatic<Compute> compute = mockStatic(Compute.class)) {
            compute.when(Compute::d6).thenReturn(1, 1);
            PrisonerRecruitmentManager.processWeeklyRolls(campaign);
            compute.verify(Compute::d6, never());
        }

        assertEquals(recruiter.getId(), prisoner.getRecruiterId(), "no roll, so the assignment stands");
        assertNull(prisoner.getRecruitmentBlockedUntil());
    }

    @Test
    void weeklyRollNeedsMinutes() {
        Person recruiter = recruiter(3);
        Person prisoner = prisoner();
        assertTrue(PrisonerRecruitmentManager.assign(campaign, recruiter, prisoner));

        try (MockedStatic<Compute> compute = mockStatic(Compute.class)) {
            PrisonerRecruitmentManager.processWeeklyRolls(campaign);
            compute.verify(Compute::d6, never());
        }
    }

    // endregion Weekly roll
}
