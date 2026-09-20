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

import static java.lang.Math.max;
import static java.lang.Math.min;
import static megamek.common.compute.Compute.randomInt;
import static mekhq.campaign.enums.DailyReportType.PERSONNEL;
import static mekhq.campaign.enums.DailyReportType.SKILL_CHECKS;
import static mekhq.campaign.personnel.skills.SkillType.S_NEGOTIATION;
import static mekhq.utilities.MHQInternationalization.getFormattedTextAt;
import static mekhq.utilities.MHQInternationalization.getTextAt;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import megamek.common.TargetRollModifier;
import megamek.common.annotations.Nullable;
import megamek.logging.MMLogger;
import mekhq.MekHQ;
import mekhq.campaign.Campaign;
import mekhq.campaign.ForceHumanResources;
import mekhq.campaign.campaignOptions.CampaignOption;
import mekhq.campaign.campaignOptions.CampaignOptions;
import mekhq.campaign.events.persons.PersonChangedEvent;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.enums.PersonnelStatus;
import mekhq.campaign.personnel.medical.InjurySPAUtility;
import mekhq.campaign.personnel.skills.ActionCheckResult;
import mekhq.campaign.personnel.skills.Skill;
import mekhq.campaign.personnel.skills.SkillCheck;
import mekhq.campaign.randomEvents.prisoners.prisonerEvents.PrisonEscapeScenario;
import mekhq.campaign.universe.Faction;
import mekhq.gui.baseComponents.immersiveDialogs.ImmersiveDialogSimple;

/**
 * Lets personnel with the Negotiation skill work on turning individual prisoners of war into willing defectors.
 *
 * <p>A recruiter is assigned to prisoners from the Personnel tab. Each day the recruiter spends a fixed budget of
 * time on their assigned prisoners in priority order; every Monday the recruiter makes a Negotiation check for each
 * prisoner they spent time on, with the prisoner's loyalty as the dominant modifier. Success makes the prisoner a
 * {@link PrisonerStatus#PRISONER_DEFECTOR}; a bad failure hardens them, and a very bad one lets them escape.</p>
 *
 * <p>All links between recruiters and prisoners are held as ids on {@link Person} and swept daily, so a dangling
 * id never does harm.</p>
 */
public class PrisonerRecruitmentManager {
    private static final MMLogger LOGGER = MMLogger.create(PrisonerRecruitmentManager.class);
    private static final String RESOURCE_BUNDLE = "mekhq.resources.PrisonerRecruitment";

    /** Minutes a recruiter can spend on prisoners per day. */
    public static final int RECRUITER_DAILY_MINUTES = Person.PRIMARY_ROLE_SUPPORT_TIME;
    /** Minutes a recruiter can spend on any one prisoner per day. */
    public static final int MAX_MINUTES_PER_PRISONER_PER_DAY = 240;
    /** Every full block of this many minutes in a week is worth -1 on the check. */
    public static final int MINUTES_PER_TIME_STEP = 360;
    public static final int MAX_TIME_BONUS = 4;
    /** Every full block of this many months in captivity is worth -1 on the check. */
    public static final int MONTHS_PER_CAPTIVITY_STEP = 3;
    public static final int MAX_CAPTIVITY_BONUS = 2;
    /** Flat difficulty applied to every check. */
    public static final int BASE_RESISTANCE_MODIFIER = 4;
    public static final int SAME_FACTION_MODIFIER = -1;
    public static final int MERCENARY_MODIFIER = -1;
    public static final int CLAN_PRISONER_MODIFIER = 2;
    /** Margins of failure at or below this harden the prisoner. */
    public static final int HARDENED_MARGIN = -4;
    /** Margins of failure at or below this also let the prisoner escape. */
    public static final int FUMBLE_MARGIN = -6;
    public static final int HARDENING_LOYALTY_CHANGE = 1;
    /** Checks with a target number at or above this are not rolled at all. */
    public static final int IMPOSSIBLE_TARGET_NUMBER = 13;
    public static final int XP_PER_CONVERSION = 1;
    public static final int XP_PER_ESCAPE = 3;
    /** Percentage chance the recruiter is hurt when a prisoner escapes. */
    public static final int ESCAPE_INJURY_CHANCE = 50;
    public static final int ESCAPE_INJURY_HITS = 1;
    private static final int MAX_HITS = 5;

    private PrisonerRecruitmentManager() {
    }

    // region Day loop

    /**
     * Day-loop entry point. Weekly checks happen on Mondays using the minutes accumulated over the previous week,
     * and then every recruiter spends today's time.
     *
     * @param campaign the campaign
     * @param isMonday whether today is the first day of a new week
     */
    public static void processNewDay(Campaign campaign, boolean isMonday) {
        cleanupInvalidAssignments(campaign);

        if (isMonday) {
            processWeeklyRolls(campaign);
            resetWeeklyMinutes(campaign);
        }

        processDailyWork(campaign);
    }

    /**
     * Spends every eligible recruiter's daily time on their assigned prisoners, in priority order.
     */
    static void processDailyWork(Campaign campaign) {
        ForceHumanResources humanResources = campaign.getPlayerForce().getHumanResources();
        LocalDate today = campaign.getLocalDate();

        for (Person recruiter : getEligibleRecruiters(campaign)) {
            if (recruiter.getRecruitmentAssignments().isEmpty()) {
                continue;
            }

            if ((recruiter.getUnit() != null) && recruiter.getUnit().isDeployed()) {
                continue;
            }

            List<Person> queue = new ArrayList<>();
            for (UUID prisonerId : recruiter.getRecruitmentAssignments()) {
                Person prisoner = humanResources.getPerson(prisonerId);
                if (isRecruitablePrisoner(prisoner) && !prisoner.isRecruitmentBlocked(today)) {
                    queue.add(prisoner);
                }
            }

            int[] minutes = allocateDailyMinutes(queue.size());
            for (int i = 0; i < queue.size(); i++) {
                queue.get(i).addRecruitmentMinutesThisWeek(minutes[i]);
            }
        }
    }

    /**
     * Splits a recruiter's daily budget across their queue: the first prisoners get the full per-prisoner
     * allowance, whoever is left when the budget runs out gets nothing.
     *
     * @param prisonerCount number of prisoners in the queue
     *
     * @return minutes for each queue position
     */
    static int[] allocateDailyMinutes(int prisonerCount) {
        int[] allocation = new int[max(0, prisonerCount)];
        int remaining = RECRUITER_DAILY_MINUTES;

        for (int i = 0; (i < allocation.length) && (remaining > 0); i++) {
            allocation[i] = min(MAX_MINUTES_PER_PRISONER_PER_DAY, remaining);
            remaining -= allocation[i];
        }

        return allocation;
    }

    static void resetWeeklyMinutes(Campaign campaign) {
        for (Person prisoner : campaign.getPlayerForce().getHumanResources().getCurrentPrisoners()) {
            prisoner.setRecruitmentMinutesThisWeek(0);
        }
    }

    /**
     * Makes the weekly Negotiation check for every prisoner that received attention this week.
     */
    static void processWeeklyRolls(Campaign campaign) {
        ForceHumanResources humanResources = campaign.getPlayerForce().getHumanResources();
        LocalDate today = campaign.getLocalDate();

        // Snapshot: an escape removes the prisoner from the roster mid-loop
        for (Person prisoner : new ArrayList<>(humanResources.getCurrentPrisoners())) {
            UUID recruiterId = prisoner.getRecruiterId();
            if (recruiterId == null) {
                continue;
            }

            Person recruiter = humanResources.getPerson(recruiterId);
            if ((recruiter == null) || !isEligibleRecruiter(recruiter)) {
                unassign(campaign, prisoner);
                continue;
            }

            if (prisoner.getPrisonerStatus().isPrisonerDefector()) {
                // Already willing, by whatever means
                unassign(campaign, prisoner);
                continue;
            }

            int minutes = prisoner.getRecruitmentMinutesThisWeek();
            if (minutes <= 0) {
                campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "noTime.report",
                      prisoner.getHyperlinkedFullTitle(), recruiter.getHyperlinkedFullTitle()));
                continue;
            }

            CampaignOptions campaignOptions = campaign.getCampaignOptions();
            List<TargetRollModifier> modifiers = calculateModifiers(prisoner,
                  campaign.getPlayerForce().getFaction(),
                  campaign.getPlayerForce().isClanForce(),
                  minutes,
                  today,
                  !campaignOptions.get(CampaignOption.USE_HIDE_LOYALTY));

            SkillCheck check = recruiter.checkSkill(S_NEGOTIATION, campaign).withExternalModifiers(modifiers);

            if (isImpossible(check.getTargetNumber().getValue())) {
                campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "impossible.report",
                      recruiter.getHyperlinkedFullTitle(), prisoner.getHyperlinkedFullTitle()));
                continue;
            }

            String reason = getFormattedTextAt(RESOURCE_BUNDLE, "check.reason", prisoner.getFullTitle());
            ActionCheckResult result = check.resolve(false, reason);
            campaign.addReport(SKILL_CHECKS, result.getReport());

            switch (determineOutcome(result.getMarginOfSuccess())) {
                case SUCCESS -> handleSuccess(campaign, recruiter, prisoner);
                case FAILURE -> campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "failure.report",
                      prisoner.getHyperlinkedFullTitle(), recruiter.getHyperlinkedFullTitle()));
                case HARDENED -> handleHardening(campaign, recruiter, prisoner, today, "hardened.report");
                case FUMBLE -> {
                    handleHardening(campaign, recruiter, prisoner, today, "fumble.report");
                    handleEscape(campaign, recruiter, prisoner);
                }
            }
        }
    }

    /**
     * @param targetNumber the check's target number
     *
     * @return true if the check cannot be passed on 2d6 and should not be rolled
     */
    static boolean isImpossible(int targetNumber) {
        return targetNumber >= IMPOSSIBLE_TARGET_NUMBER;
    }

    /**
     * @param marginOfSuccess the margin of success of the weekly check
     *
     * @return what happens to the prisoner
     */
    static PrisonerRecruitmentOutcome determineOutcome(int marginOfSuccess) {
        if (ActionCheckResult.isSuccess(marginOfSuccess)) {
            return PrisonerRecruitmentOutcome.SUCCESS;
        } else if (marginOfSuccess <= FUMBLE_MARGIN) {
            return PrisonerRecruitmentOutcome.FUMBLE;
        } else if (marginOfSuccess <= HARDENED_MARGIN) {
            return PrisonerRecruitmentOutcome.HARDENED;
        } else {
            return PrisonerRecruitmentOutcome.FAILURE;
        }
    }

    // endregion Day loop

    // region Modifiers

    /**
     * Builds the modifiers for a weekly check. Positive values make the check harder.
     *
     * @param prisoner        the prisoner being worked on
     * @param campaignFaction the player's faction
     * @param isClanCampaign  whether the player's force is a Clan force
     * @param minutesThisWeek minutes the recruiter spent on the prisoner this week
     * @param today           the current date
     * @param showLoyalty     whether the loyalty score may appear in the modifier label
     *
     * @return the modifiers, never empty
     */
    static List<TargetRollModifier> calculateModifiers(Person prisoner, Faction campaignFaction,
          boolean isClanCampaign, int minutesThisWeek, LocalDate today, boolean showLoyalty) {
        List<TargetRollModifier> modifiers = new ArrayList<>();

        modifiers.add(new TargetRollModifier(BASE_RESISTANCE_MODIFIER,
              getTextAt(RESOURCE_BUNDLE, "modifier.baseResistance")));

        int loyalty = prisoner.getBaseLoyalty();
        int loyaltyModifier = calculateLoyaltyModifier(loyalty);
        if (loyaltyModifier != 0) {
            String band = getTextAt(RESOURCE_BUNDLE, "loyaltyBand." + getLoyaltyBandKey(loyalty));
            String label = showLoyalty ?
                                 getFormattedTextAt(RESOURCE_BUNDLE, "modifier.loyalty.shown", band, loyalty) :
                                 getFormattedTextAt(RESOURCE_BUNDLE, "modifier.loyalty.hidden", band);
            modifiers.add(new TargetRollModifier(loyaltyModifier, label));
        }

        int timeModifier = calculateTimeModifier(minutesThisWeek);
        if (timeModifier != 0) {
            modifiers.add(new TargetRollModifier(timeModifier, getTextAt(RESOURCE_BUNDLE, "modifier.time")));
        }

        Faction originFaction = prisoner.getOriginFaction();
        if ((originFaction != null) && (campaignFaction != null) && originFaction.equals(campaignFaction)) {
            modifiers.add(new TargetRollModifier(SAME_FACTION_MODIFIER,
                  getTextAt(RESOURCE_BUNDLE, "modifier.sameFaction")));
        }

        if ((originFaction != null) && originFaction.isMercenary()) {
            modifiers.add(new TargetRollModifier(MERCENARY_MODIFIER, getTextAt(RESOURCE_BUNDLE, "modifier.mercenary")));
        }

        if (prisoner.isClanPersonnel() && !isClanCampaign) {
            modifiers.add(new TargetRollModifier(CLAN_PRISONER_MODIFIER,
                  getTextAt(RESOURCE_BUNDLE, "modifier.clanPrisoner")));
        }

        LocalDate captureDate = prisoner.getCaptureDate();
        if (captureDate == null) {
            captureDate = prisoner.getJoinedCampaign();
        }
        int captivityModifier = calculateCaptivityModifier(captureDate, today);
        if (captivityModifier != 0) {
            modifiers.add(new TargetRollModifier(captivityModifier, getTextAt(RESOURCE_BUNDLE, "modifier.captivity")));
        }

        return modifiers;
    }

    /**
     * The more loyal the prisoner, the harder the check - steeply so at the top.
     *
     * @param baseLoyalty the prisoner's loyalty score
     *
     * @return the modifier
     */
    static int calculateLoyaltyModifier(int baseLoyalty) {
        if (baseLoyalty <= 6) {
            return -2;
        } else if (baseLoyalty <= 9) {
            return 0;
        } else if (baseLoyalty <= 12) {
            return 2;
        } else if (baseLoyalty <= 14) {
            return 5;
        } else if (baseLoyalty <= 16) {
            return 8;
        } else {
            return 12;
        }
    }

    static String getLoyaltyBandKey(int baseLoyalty) {
        if (baseLoyalty <= 6) {
            return "wavering";
        } else if (baseLoyalty <= 9) {
            return "neutral";
        } else if (baseLoyalty <= 12) {
            return "firm";
        } else if (baseLoyalty <= 14) {
            return "staunch";
        } else if (baseLoyalty <= 16) {
            return "devoted";
        } else {
            return "unshakeable";
        }
    }

    static int calculateTimeModifier(int minutesThisWeek) {
        return -min(MAX_TIME_BONUS, max(0, minutesThisWeek) / MINUTES_PER_TIME_STEP);
    }

    static int calculateCaptivityModifier(@Nullable LocalDate captureDate, LocalDate today) {
        if ((captureDate == null) || captureDate.isAfter(today)) {
            return 0;
        }

        long months = ChronoUnit.MONTHS.between(captureDate, today);
        return -(int) min(MAX_CAPTIVITY_BONUS, months / MONTHS_PER_CAPTIVITY_STEP);
    }

    // endregion Modifiers

    // region Outcomes

    private static void handleSuccess(Campaign campaign, Person recruiter, Person prisoner) {
        unassign(campaign, prisoner);
        // log=false: a "made prisoner" log entry was already written at capture
        prisoner.setPrisonerStatus(campaign, PrisonerStatus.PRISONER_DEFECTOR, false);
        recruiter.awardXP(campaign, XP_PER_CONVERSION);

        campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "success.report",
              prisoner.getHyperlinkedFullTitle(), recruiter.getHyperlinkedFullTitle(), XP_PER_CONVERSION));

        String message = getFormattedTextAt(RESOURCE_BUNDLE, "success.message",
              campaign.getCommanderAddress(), prisoner.getFullTitle());
        String outOfCharacter = getFormattedTextAt(RESOURCE_BUNDLE, "success.ooc", prisoner.getFullTitle());
        // Decline first: closing the dialog returns choice 0 and must never recruit
        List<String> buttons = List.of(getTextAt(RESOURCE_BUNDLE, "later.button"),
              getTextAt(RESOURCE_BUNDLE, "recruit.button"));

        ImmersiveDialogSimple dialog = new ImmersiveDialogSimple(campaign, recruiter, prisoner, message, buttons,
              outOfCharacter, null, false);

        if (dialog.getDialogChoice() == 1) {
            recruitDefector(campaign, prisoner);
        }
    }

    /**
     * Turns a willing defector into a free, active member of the force. Identical to the Personnel tab's Recruit
     * action.
     *
     * @param campaign the campaign
     * @param prisoner the prisoner-defector to recruit
     */
    public static void recruitDefector(Campaign campaign, Person prisoner) {
        if (!prisoner.getPrisonerStatus().isPrisonerDefector()) {
            return;
        }

        prisoner.setPrisonerStatus(campaign, PrisonerStatus.FREE, true);
        // Without this the defector would be recruited as a camp follower
        prisoner.changeStatus(campaign, campaign.getLocalDate(), PersonnelStatus.ACTIVE);
    }

    private static void handleHardening(Campaign campaign, Person recruiter, Person prisoner, LocalDate today,
          String reportKey) {
        prisoner.changeLoyalty(HARDENING_LOYALTY_CHANGE);
        unassign(campaign, prisoner);

        LocalDate blockedUntil = today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        prisoner.setRecruitmentBlockedUntil(blockedUntil);

        campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, reportKey,
              prisoner.getHyperlinkedFullTitle(), recruiter.getHyperlinkedFullTitle(),
              MekHQ.getMHQOptions().getDisplayFormattedDate(blockedUntil)));
    }

    private static void handleEscape(Campaign campaign, Person recruiter, Person prisoner) {
        String prisonerTitle = prisoner.getFullTitle();

        // Mirrors RandomEventEffectsManager.eventEffectEscape: off the roster first, then the scenario
        campaign.getPlayerForce().getHumanResources().removePerson(campaign, prisoner, false);
        recruiter.awardXP(campaign, XP_PER_ESCAPE);
        campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "escape.report", prisonerTitle,
              recruiter.getHyperlinkedFullTitle(), XP_PER_ESCAPE));

        if (randomInt(100) < ESCAPE_INJURY_CHANCE) {
            injureRecruiter(campaign, recruiter, ESCAPE_INJURY_HITS);
            campaign.addReport(PERSONNEL, getFormattedTextAt(RESOURCE_BUNDLE, "escapeInjury.report",
                  recruiter.getHyperlinkedFullTitle(), prisonerTitle));
        }

        if (campaign.hasActiveAtBContract()) {
            List<AbstractContract> contracts = campaign.getActiveContracts();
            Collections.shuffle(contracts);

            Set<Person> escapees = new HashSet<>();
            escapees.add(prisoner);
            new PrisonEscapeScenario(campaign, contracts.getFirst(), escapees);
        }
    }

    /**
     * Hurts the recruiter without ever killing them. Same rules as the prisoner random events.
     */
    static void injureRecruiter(Campaign campaign, Person recruiter, int hits) {
        CampaignOptions campaignOptions = campaign.getCampaignOptions();
        int priorHits = recruiter.getTotalInjurySeverity();

        int wounds = InjurySPAUtility.adjustInjuriesAndFatigueForSPAs(recruiter,
              campaignOptions.get(CampaignOption.USE_INJURY_FATIGUE),
              campaignOptions.get(CampaignOption.FATIGUE_RATE),
              max(hits, 1));

        if (priorHits + wounds > MAX_HITS) {
            wounds = MAX_HITS - priorHits;
        }

        if (wounds <= 0) {
            return;
        }

        recruiter.setHitsPrior(priorHits);
        recruiter.setHits(priorHits + wounds);

        if (campaignOptions.isUseAdvancedMedical()) {
            recruiter.diagnose(campaign, wounds);
        }

        MekHQ.triggerEvent(new PersonChangedEvent(recruiter));
    }

    // endregion Outcomes

    // region Assignments

    /**
     * @param person the person to check
     *
     * @return true if the person may work on recruiting prisoners
     */
    public static boolean isEligibleRecruiter(@Nullable Person person) {
        return (person != null) &&
                     person.getStatus().isActive() &&
                     person.getPrisonerStatus().isFreeOrBondsman() &&
                     person.hasSkill(S_NEGOTIATION);
    }

    /**
     * @param recruiter the recruiter
     *
     * @return how many prisoners the recruiter may work on at once
     */
    public static int getMaxAssignments(Person recruiter) {
        Skill skill = recruiter.getSkill(S_NEGOTIATION);
        return (skill == null) ? 0 : skill.getLevel() + 1;
    }

    /**
     * @param prisoner the person to check
     *
     * @return true if the person is a prisoner who still needs persuading
     */
    public static boolean isRecruitablePrisoner(@Nullable Person prisoner) {
        return (prisoner != null) &&
                     prisoner.getPrisonerStatus().isPrisoner() &&
                     prisoner.getStatus().isActiveFlexible();
    }

    public static int getCurrentLoad(Person recruiter) {
        return recruiter.getRecruitmentAssignments().size();
    }

    /**
     * @param campaign the campaign
     *
     * @return everyone who may recruit, best negotiators first
     */
    public static List<Person> getEligibleRecruiters(Campaign campaign) {
        List<Person> recruiters = new ArrayList<>();
        for (Person person : campaign.getPlayerForce().getHumanResources().getActivePersonnel(false, false)) {
            if (isEligibleRecruiter(person)) {
                recruiters.add(person);
            }
        }

        recruiters.sort(Comparator.comparingInt((Person person) -> -getMaxAssignments(person))
                              .thenComparing(Person::getFullTitle));
        return recruiters;
    }

    /**
     * @return true if the recruiter could take on the prisoner right now
     */
    public static boolean canAssign(Campaign campaign, Person recruiter, Person prisoner) {
        if (!isEligibleRecruiter(recruiter) ||
                  !isRecruitablePrisoner(prisoner) ||
                  prisoner.isRecruitmentBlocked(campaign.getLocalDate())) {
            return false;
        }

        boolean alreadyAssigned = recruiter.getId().equals(prisoner.getRecruiterId());
        return alreadyAssigned || (getCurrentLoad(recruiter) < getMaxAssignments(recruiter));
    }

    /**
     * Assigns the recruiter to the prisoner, at the back of the recruiter's queue. Any previous recruiter is
     * unassigned first.
     *
     * @return true if the link was made
     */
    public static boolean assign(Campaign campaign, Person recruiter, Person prisoner) {
        if (!canAssign(campaign, recruiter, prisoner)) {
            return false;
        }

        UUID previousRecruiterId = prisoner.getRecruiterId();
        if ((previousRecruiterId != null) && !previousRecruiterId.equals(recruiter.getId())) {
            unassign(campaign, prisoner);
        }

        prisoner.setRecruiterId(recruiter.getId());
        recruiter.addRecruitmentAssignment(prisoner.getId());

        MekHQ.triggerEvent(new PersonChangedEvent(prisoner));
        MekHQ.triggerEvent(new PersonChangedEvent(recruiter));
        return true;
    }

    /**
     * Removes the prisoner's recruiter. Tolerates a recruiter who no longer exists.
     */
    public static void unassign(Campaign campaign, Person prisoner) {
        UUID recruiterId = prisoner.getRecruiterId();
        if (recruiterId == null) {
            return;
        }

        Person recruiter = campaign.getPlayerForce().getHumanResources().getPerson(recruiterId);
        if (recruiter != null) {
            recruiter.removeRecruitmentAssignment(prisoner.getId());
            MekHQ.triggerEvent(new PersonChangedEvent(recruiter));
        }

        prisoner.setRecruiterId(null);
        MekHQ.triggerEvent(new PersonChangedEvent(prisoner));
    }

    /**
     * Moves the prisoner to the front of their recruiter's queue.
     */
    public static void moveToTop(Campaign campaign, Person prisoner) {
        UUID recruiterId = prisoner.getRecruiterId();
        if (recruiterId == null) {
            return;
        }

        Person recruiter = campaign.getPlayerForce().getHumanResources().getPerson(recruiterId);
        if (recruiter == null) {
            unassign(campaign, prisoner);
            return;
        }

        recruiter.moveRecruitmentAssignmentToTop(prisoner.getId());
        MekHQ.triggerEvent(new PersonChangedEvent(recruiter));
    }

    /**
     * Drops every link touching the person, as prisoner and as recruiter. Null-tolerant in both directions.
     */
    public static void unlinkAll(Campaign campaign, Person person) {
        unassign(campaign, person);

        if (person.getRecruitmentAssignments().isEmpty()) {
            return;
        }

        ForceHumanResources humanResources = campaign.getPlayerForce().getHumanResources();
        for (UUID prisonerId : new ArrayList<>(person.getRecruitmentAssignments())) {
            Person prisoner = humanResources.getPerson(prisonerId);
            if ((prisoner != null) && person.getId().equals(prisoner.getRecruiterId())) {
                prisoner.setRecruiterId(null);
                MekHQ.triggerEvent(new PersonChangedEvent(prisoner));
            }
        }

        if (!person.getRecruitmentAssignments().isEmpty()) {
            person.clearRecruitmentAssignments();
            MekHQ.triggerEvent(new PersonChangedEvent(person));
        }
    }

    /**
     * Restores the invariants: every link is mutual, every recruiter is eligible and within their cap, every
     * assigned prisoner is still a prisoner, and expired refusals are cleared.
     */
    static void cleanupInvalidAssignments(Campaign campaign) {
        ForceHumanResources humanResources = campaign.getPlayerForce().getHumanResources();
        LocalDate today = campaign.getLocalDate();

        for (Person person : new ArrayList<>(humanResources.getPersonnel())) {
            // Prisoner side
            UUID recruiterId = person.getRecruiterId();
            if (recruiterId != null) {
                Person recruiter = humanResources.getPerson(recruiterId);
                boolean valid = isEligibleRecruiter(recruiter) &&
                                      isRecruitablePrisoner(person) &&
                                      recruiter.getRecruitmentAssignments().contains(person.getId());
                if (!valid) {
                    unassign(campaign, person);
                }
            }

            // Recruiter side
            for (UUID prisonerId : new ArrayList<>(person.getRecruitmentAssignments())) {
                Person prisoner = humanResources.getPerson(prisonerId);
                if (!isRecruitablePrisoner(prisoner) || !person.getId().equals(prisoner.getRecruiterId())) {
                    person.removeRecruitmentAssignment(prisonerId);
                    if ((prisoner != null) && person.getId().equals(prisoner.getRecruiterId())) {
                        prisoner.setRecruiterId(null);
                    }
                }
            }

            if (!person.getRecruitmentAssignments().isEmpty() && !isEligibleRecruiter(person)) {
                unlinkAll(campaign, person);
            }

            // Over the cap (skill lowered, etc.): drop from the back of the queue
            while (getCurrentLoad(person) > getMaxAssignments(person)) {
                UUID lastId = person.getRecruitmentAssignments().getLast();
                Person prisoner = humanResources.getPerson(lastId);
                person.removeRecruitmentAssignment(lastId);
                if ((prisoner != null) && person.getId().equals(prisoner.getRecruiterId())) {
                    prisoner.setRecruiterId(null);
                }
            }

            LocalDate blockedUntil = person.getRecruitmentBlockedUntil();
            if ((blockedUntil != null) && today.isAfter(blockedUntil)) {
                person.setRecruitmentBlockedUntil(null);
            }

            if (!person.getPrisonerStatus().isCurrentPrisoner() && (person.getRecruitmentMinutesThisWeek() > 0)) {
                person.setRecruitmentMinutesThisWeek(0);
            }
        }
    }

    // endregion Assignments
}
