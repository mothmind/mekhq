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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import megamek.common.Player;
import megamek.common.Report;
import megamek.common.annotations.Nullable;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.Game;
import megamek.common.units.EjectedCrew;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.Server;
import mekhq.CampaignGameManager;
import mekhq.MekHQ;
import mekhq.campaign.Campaign;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.pilotChatter.BattleEventDetector.Trigger;

/**
 * Gives the pilots in a hosted battle a voice. It listens to the server's own game from inside MekHQ; after each
 * phase it works out what happened to whom, picks who speaks up, and asks the configured model for their lines in
 * the background, so the game never waits on it. Each line goes into the campaign's pilot journal and out to the
 * players over chat.
 * <p>
 * If the model cannot be reached, the pilots fall silent for the rest of the battle and the players are told once.
 */
public class PilotChatter extends GameListenerAdapter {
    private static final MMLogger LOGGER = MMLogger.create(PilotChatter.class);

    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final int QUEUE_LIMIT = 12;
    static final int MAX_WORDS_BEFORE_CUT = 4;
    private static final String TRAILING_PUNCTUATION = "[\\s\\p{Punct}\\u2013\\u2014\\u2026]+$";

    /**
     * Where lines come from.
     */
    @FunctionalInterface
    interface LineSource {
        Optional<String> generate(String system, String prompt) throws IOException;
    }

    /**
     * Where lines go.
     */
    interface Delivery {
        /**
         * @param listeners the players who hear it, or {@code null} for everyone
         */
        void deliver(@Nullable List<Player> listeners, String callName, String line);

        void notice(String message);
    }

    private final Campaign campaign;
    private final String battle;
    private final String enemyFaction;
    private final String endpoint;
    private final BattleBriefing briefing;
    private final ChatterTuning tuning;
    private final PilotJournal journal;
    private final LineSource source;
    private final Delivery delivery;
    private final Executor executor;
    private final IntUnaryOperator roll;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean silenced;

    private Game game;
    private BattleEventDetector detector;
    private int reportRound;
    private int reportsSeen;

    PilotChatter(Campaign campaign, String battle, @Nullable String enemyFaction, String endpoint,
          BattleBriefing briefing, ChatterTuning tuning, PilotJournal journal, LineSource source, Delivery delivery,
          Executor executor, IntUnaryOperator roll) {
        this.campaign = campaign;
        this.battle = battle;
        this.enemyFaction = enemyFaction;
        this.endpoint = endpoint;
        this.briefing = briefing;
        this.tuning = tuning;
        this.journal = journal;
        this.source = source;
        this.delivery = delivery;
        this.executor = executor;
        this.roll = roll;
    }

    /**
     * Gives the pilots of a battle about to be hosted their voices, unless pilot chatter is switched off.
     *
     * @return the chatter, or {@code null} if it is off
     */
    public static @Nullable PilotChatter attach(Server server, Campaign campaign, Scenario scenario) {
        ChatterSettings settings = ChatterSettings.fromOptions(MekHQ.getMHQOptions());
        if (!settings.enabled() || !(server.getGame() instanceof Game game)) {
            return null;
        }

        try {
            String directory = MekHQ.getCampaignsDirectory().getValue();
            Path campaigns = Path.of(((directory == null) || directory.isBlank()) ? "campaigns" : directory);
            String forceName = campaign.getPlayerForce().getName();
            PilotJournal journal = new PilotJournal(PilotJournal.fileFor(campaigns, forceName));
            BattleBriefing briefing = new BattleBriefing(CompanyLore.read(CompanyLore.fileFor(campaigns, forceName)),
                  forceName, BattleBriefing.mission(campaign, scenario));
            MessagesApiClient client = new MessagesApiClient(settings);

            PilotChatter chatter = new PilotChatter(campaign, battleName(campaign, scenario),
                  enemyFaction(campaign, scenario), settings.endpoint(), briefing,
                  ChatterTuning.fromOptions(MekHQ.getMHQOptions()), journal, client::generate,
                  serverDelivery(server), backgroundExecutor(), Compute::randomInt);
            chatter.watch(game);
            if (server.getGameManager() instanceof CampaignGameManager campaignGameManager) {
                campaignGameManager.addGameReplacedListener(chatter::follow);
            }
            return chatter;
        } catch (RuntimeException ex) {
            LOGGER.error(ex, "Pilot chatter could not start; the battle goes ahead without it");
            return null;
        }
    }

    void watch(Game newGame) {
        game = newGame;
        detector = new BattleEventDetector();
        detector.prime(newGame);
        reportRound = newGame.getCurrentRound();
        reportsSeen = newGame.getReports(reportRound).size();
        newGame.addGameListener(this);
    }

    /**
     * Moves to a game the server has swapped in, such as a save a player loaded.
     */
    void follow(Game newGame) {
        game.removeGameListener(this);
        watch(newGame);
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent event) {
        try {
            reactTo(event.getNewPhase());
        } catch (RuntimeException ex) {
            LOGGER.error(ex, "Pilot chatter failed at the {} phase; the battle carries on without it",
                  event.getNewPhase());
        }
    }

    private void reactTo(GamePhase phase) {
        if (!phase.isReport() || phase.isInitiativeReport() || phase.isVictory()) {
            detector.prime(game);
            return;
        }
        List<Report> newReports = newReports();
        if (silenced) {
            detector.detect(game, newReports, false);
            return;
        }
        boolean inAction = phase.isMovementReport() || phase.isFiringReport() || phase.isPhysicalReport();
        Player campaignSide = PilotDossier.campaignSide(game, campaign);
        List<Trigger> triggers = detector.detect(game, newReports, inAction,
              entity -> ChatAudience.of(game, entity).viewer());
        for (Trigger trigger : ChatterPolicy.choose(triggers, roll, tuning.chattiness())) {
            try {
                speak(trigger, campaignSide);
            } catch (RuntimeException ex) {
                LOGGER.error(ex, "Pilot chatter skipped unit {}", trigger.entityId());
            }
        }
    }

    private List<Report> newReports() {
        int round = game.getCurrentRound();
        List<Report> reports = game.getReports(round);
        if (round != reportRound) {
            reportRound = round;
            reportsSeen = 0;
        }
        int from = Math.min(reportsSeen, reports.size());
        reportsSeen = reports.size();
        return List.copyOf(reports.subList(from, reports.size()));
    }

    private void speak(Trigger trigger, @Nullable Player campaignSide) {
        Entity entity = game.getEntity(trigger.entityId());
        if (entity == null) {
            entity = game.getOutOfGameEntity(trigger.entityId());
        }
        if ((entity == null) || (entity.getCrew() == null)) {
            return;
        }
        boolean dead = BattleEventDetector.pilotKilled(entity);
        if (dead && (trigger.event() != ChatterEvent.DESTROYED) && (trigger.event() != ChatterEvent.HEADSHOT)) {
            return;
        }

        if (!tuning.enemyChatter() && PilotDossier.isEnemy(entity.getOwner(), campaignSide)) {
            return;
        }
        ChatAudience audience = ChatAudience.of(game, entity);
        if (!audience.isHeard()) {
            return;
        }

        PilotDossier dossier = PilotDossier.forEntity(entity, campaign, battle, enemyFaction, campaignSide);
        int round = game.getCurrentRound();
        int team = (entity.getOwner() == null) ? 0 : entity.getOwner().getTeam();
        String briefingText = dossier.campaignPilot() ?
                                    briefing.forCampaignPilot(comrades(entity)) :
                                    briefing.forOtherPilot(PilotDossier.isEnemy(entity.getOwner(), campaignSide));
        String recall = (dossier.campaignPilot() && (roll.applyAsInt(100) < tuning.loreChance())) ?
                              briefing.recall(roll.applyAsInt(BattleBriefing.RECALLED.size())) :
                              "";
        String prompt = ChatterPrompt.build(dossier, briefingText, recall, team, trigger, dead, round,
              journal.recentBy(dossier.key(), ChatterPrompt.OWN_LINES,
                    entry -> !entry.battle().equals(battle) || audience.heard(entry)),
              journal.recentInBattle(battle, ChatterPrompt.BATTLE_LINES, audience::heard));
        executor.execute(() -> {
            try {
                generate(audience, dossier, trigger.event(), prompt, round, team);
            } catch (RuntimeException ex) {
                LOGGER.error(ex, "Pilot chatter lost a line from {}", dossier.callName());
            }
        });
    }

    /**
     * @return the company's other pilots in the battle, with those already out of the fight marked
     */
    private List<String> comrades(Entity speaker) {
        List<String> comrades = new ArrayList<>();
        for (Entity entity : game.getEntitiesVector()) {
            if ((entity.getId() == speaker.getId()) || (entity instanceof EjectedCrew)) {
                continue;
            }
            String comrade = PilotDossier.comrade(entity, campaign);
            if (comrade != null) {
                boolean down = entity.isDestroyed() || entity.isDoomed() || BattleEventDetector.pilotKilled(entity);
                comrades.add(comrade + (down ? ", out of the fight" : ""));
            }
        }
        return comrades;
    }

    private void generate(ChatAudience audience, PilotDossier dossier, ChatterEvent event, String prompt, int round,
          int team) {
        if (silenced) {
            return;
        }
        Optional<String> line;
        try {
            line = source.generate(ChatterPrompt.SYSTEM, prompt);
            failures.set(0);
        } catch (IOException ex) {
            LOGGER.warn("Pilot chatter got no line from {}: {}", endpoint, ex.getMessage());
            if ((failures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) && !silenced) {
                silenced = true;
                delivery.notice("Pilot chatter is off for this battle: " + endpoint + " failed "
                                      + MAX_CONSECUTIVE_FAILURES + " times in a row (" + ex.getMessage()
                                      + "). The host can check it in MekHQ Options, Advanced.");
            }
            return;
        }

        line.map(spoken -> (event == ChatterEvent.HEADSHOT) ? cutOff(spoken, roll) : spoken).ifPresent(spoken -> {
            journal.append(new PilotJournal.Entry(Instant.now().toString(), String.valueOf(campaign.getLocalDate()),
                  battle, round, dossier.key(), dossier.callName(), event.name(), spoken, team,
                  dossier.pronouns(), audience.listenerIds()));
            delivery.deliver(audience.listeners(), dossier.callName(), spoken);
        });
    }

    /**
     * Cuts a line off the way a shot to the head would: a few whole words, then half of the next one if it is long
     * enough to split, then a dash.
     *
     * @param line the line the pilot was saying
     * @param roll returns a roll from 0 to {@code bound - 1}
     *
     * @return the start of the line, ending in a dash
     */
    static String cutOff(String line, IntUnaryOperator roll) {
        String[] words = line.strip().split("\\s+");
        int whole = (words.length <= 1) ? 0 : 1 + roll.applyAsInt(Math.min(MAX_WORDS_BEFORE_CUT, words.length - 1));
        StringBuilder cut = new StringBuilder(String.join(" ", Arrays.copyOfRange(words, 0, whole)));
        String next = words[whole].replaceAll(TRAILING_PUNCTUATION, "");
        if ((whole == 0) || (next.length() > 3)) {
            cut.append(cut.isEmpty() ? "" : " ").append(next, 0, (next.length() + 1) / 2);
        }
        return cut.toString().replaceAll(TRAILING_PUNCTUATION, "") + "\u2014";
    }

    boolean isSilenced() {
        return silenced;
    }

    static String battleName(Campaign campaign, Scenario scenario) {
        return campaign.getLocalDate() + " " + scenario.getName() + " (#" + scenario.getId() + ")";
    }

    static @Nullable String enemyFaction(Campaign campaign, Scenario scenario) {
        AbstractContract contract = campaign.getContract(scenario.getMissionId());
        if ((contract != null) && (contract.getEnemyFaction() != null)) {
            return contract.getEnemyFaction().getFullName(campaign.getGameYear());
        }
        return null;
    }

    static Delivery serverDelivery(Server server) {
        return new Delivery() {
            @Override
            public void deliver(@Nullable List<Player> listeners, String callName, String line) {
                if (listeners == null) {
                    server.sendChat(callName, line);
                } else {
                    for (Player player : listeners) {
                        server.sendChat(player.getId(), callName, line);
                    }
                }
            }

            @Override
            public void notice(String message) {
                server.sendServerChat(message);
            }
        };
    }

    private static Executor backgroundExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                  Thread thread = new Thread(runnable, "PilotChatter");
                  thread.setDaemon(true);
                  return thread;
              }, new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
