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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import megamek.common.Player;
import megamek.common.Report;
import megamek.common.annotations.Nullable;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.EjectedCrew;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/**
 * Works out what happened to each pilot since the last report phase, by comparing every unit with how it stood then
 * and reading the new battle reports about it.
 */
public class BattleEventDetector {
    static final int EXPLOSION_REPORT = 6390;
    static final int MAX_CONTEXT_LINES = 6;
    static final String UNSEEN = "[unseen]";

    private final Map<Integer, Snapshot> snapshots = new HashMap<>();

    /**
     * Something that happened to one pilot's unit.
     *
     * @param entityId the unit
     * @param event    the most important thing that happened to it
     * @param detail   specifics worth mentioning, such as who they destroyed; may be empty
     * @param context  the battle reports about the unit, as plain text
     */
    public record Trigger(int entityId, ChatterEvent event, String detail, List<String> context) {}

    record Snapshot(int armorAndStructure, int head, boolean destroyed, int crewHits, boolean ejected,
          boolean posing) {
        static Snapshot of(Entity entity) {
            return new Snapshot(entity.getTotalArmor() + entity.getTotalInternal(), head(entity),
                  entity.isDestroyed() || entity.isDoomed(),
                  (entity.getCrew() == null) ? 0 : entity.getCrew().getHits(),
                  (entity.getCrew() != null) && entity.getCrew().isEjected(),
                  entity.isPosing());
        }

        private static int head(Entity entity) {
            if (!(entity instanceof Mek)) {
                return 0;
            }
            return Math.max(0, entity.getArmor(Mek.LOC_HEAD)) + Math.max(0, entity.getInternal(Mek.LOC_HEAD));
        }
    }

    /**
     * @return whether the unit's pilot is dead, or will be once MegaMek finishes the phase
     */
    static boolean pilotKilled(Entity entity) {
        return (entity.getCrew() != null) && (entity.getCrew().isDead() || entity.getCrew().isDoomed());
    }

    /**
     * @return {@link ChatterEvent#HEADSHOT} when the pilot died in a phase that hit the head, otherwise
     *       {@link ChatterEvent#DESTROYED}
     */
    static ChatterEvent destruction(Snapshot before, Snapshot now, Entity entity) {
        boolean headHit = now.head() < before.head();
        return (pilotKilled(entity) && headHit) ? ChatterEvent.HEADSHOT : ChatterEvent.DESTROYED;
    }

    /**
     * Records how any unit not seen before stands now, without reporting anything, so the next report phase speaks
     * only to what happened after it arrived.
     */
    public void prime(Game game) {
        for (Entity entity : game.getEntitiesVector()) {
            snapshots.putIfAbsent(entity.getId(), Snapshot.of(entity));
        }
    }

    /**
     * @param game       the game, in a report phase
     * @param newReports the reports added since the last call
     * @param inAction   whether units that feature in the reports count as being in action this phase
     *
     * @return one trigger per unit with something to talk about, the most important event for each
     */
    public List<Trigger> detect(Game game, List<Report> newReports, boolean inAction) {
        return detect(game, newReports, inAction, entity -> null);
    }

    /**
     * @param viewerOf for each unit, the player whose knowledge its pilot may draw on, or {@code null} for all of it;
     *                 the battle reports and kills are described as that player saw them
     *
     * @see #detect(Game, List, boolean)
     */
    public List<Trigger> detect(Game game, List<Report> newReports, boolean inAction,
          Function<Entity, Player> viewerOf) {
        Map<Integer, Player> viewers = new HashMap<>();
        Function<Integer, Player> viewer = entityId -> viewers.computeIfAbsent(entityId, id -> {
            Entity entity = findEntity(game, id);
            return (entity == null) ? null : viewerOf.apply(entity);
        });
        Map<Integer, List<String>> context = reportContext(game, newReports, viewer);
        Map<Integer, ChatterEvent> events = new LinkedHashMap<>();
        Map<Integer, String> details = new HashMap<>();

        for (Entity entity : game.getEntitiesVector()) {
            if (entity instanceof EjectedCrew) {
                continue;
            }
            Snapshot now = Snapshot.of(entity);
            Snapshot before = snapshots.put(entity.getId(), now);
            if (before == null) {
                if (now.posing()) {
                    raise(events, entity.getId(), ChatterEvent.POSE);
                }
                continue;
            }

            if (now.destroyed() && !before.destroyed()) {
                ChatterEvent destruction = destruction(before, now, entity);
                raise(events, entity.getId(), destruction);
                Entity killer = game.getEntity(entity.getKillerId());
                if ((killer != null) && (killer.getId() != entity.getId())) {
                    raise(events, killer.getId(), ChatterEvent.KILL);
                    details.put(killer.getId(), killDetail(entity, destruction, viewer.apply(killer.getId())));
                }
            }
            if (now.ejected() && !before.ejected()) {
                raise(events, entity.getId(), ChatterEvent.EJECTED);
            }
            if ((now.crewHits() > before.crewHits()) && !now.destroyed()) {
                raise(events, entity.getId(), ChatterEvent.PILOT_HURT);
            }
            if (now.armorAndStructure() < before.armorAndStructure()) {
                raise(events, entity.getId(), ChatterEvent.DAMAGED);
                details.putIfAbsent(entity.getId(),
                      "They took " + (before.armorAndStructure() - now.armorAndStructure()) + " damage.");
            }
            if (now.posing() && !before.posing()) {
                raise(events, entity.getId(), ChatterEvent.POSE);
            }
        }

        for (Integer entityId : new ArrayList<>(snapshots.keySet())) {
            if (game.getEntity(entityId) != null) {
                continue;
            }
            Snapshot before = snapshots.remove(entityId);
            Entity gone = game.getOutOfGameEntity(entityId);
            if ((gone != null) && !before.destroyed() && (gone.isDestroyed() || gone.isDoomed())) {
                ChatterEvent destruction = destruction(before, Snapshot.of(gone), gone);
                raise(events, entityId, destruction);
                Entity killer = game.getEntity(gone.getKillerId());
                if (killer != null) {
                    raise(events, killer.getId(), ChatterEvent.KILL);
                    details.put(killer.getId(), killDetail(gone, destruction, viewer.apply(killer.getId())));
                }
            }
        }

        for (Report report : newReports) {
            if ((report.messageId == EXPLOSION_REPORT) && (game.getEntity(report.subject) != null)) {
                raise(events, report.subject, ChatterEvent.INTERNAL_EXPLOSION);
            }
        }

        if (inAction) {
            for (Integer entityId : context.keySet()) {
                Entity entity = game.getEntity(entityId);
                if ((entity != null) && !(entity instanceof EjectedCrew) && !entity.isDestroyed()) {
                    raise(events, entityId, ChatterEvent.IN_ACTION);
                }
            }
        }

        List<Trigger> triggers = new ArrayList<>();
        events.forEach((entityId, event) -> triggers.add(new Trigger(entityId, event,
              details.getOrDefault(entityId, ""), context.getOrDefault(entityId, List.of()))));
        return triggers;
    }

    private static String killDetail(Entity victim, ChatterEvent destruction, @Nullable Player viewer) {
        if ((viewer != null) && !victim.hasSeenEntity(viewer)) {
            return "They destroyed an enemy unit their side never saw.";
        }
        return (destruction == ChatterEvent.HEADSHOT) ?
                     "They destroyed " + victim.getShortName() + " with a shot to the head that killed its pilot." :
                     "They destroyed " + victim.getShortName() + ".";
    }

    private static @Nullable Entity findEntity(Game game, int entityId) {
        Entity entity = game.getEntity(entityId);
        return (entity != null) ? entity : game.getOutOfGameEntity(entityId);
    }

    private static void raise(Map<Integer, ChatterEvent> events, int entityId, ChatterEvent event) {
        events.merge(entityId, event, (current, candidate) -> (candidate.ordinal() < current.ordinal()) ?
                                                                    candidate :
                                                                    current);
    }

    private static Map<Integer, List<String>> reportContext(Game game, List<Report> reports,
          Function<Integer, Player> viewer) {
        Map<Integer, List<String>> context = new LinkedHashMap<>();
        for (Report report : reports) {
            if (report.subject == Entity.NONE) {
                continue;
            }
            Report seen = asSeenBy(report, game, viewer.apply(report.subject));
            if (seen == null) {
                continue;
            }
            String text = plainText(seen);
            if (text.isEmpty()) {
                continue;
            }
            List<String> lines = context.computeIfAbsent(report.subject, id -> new ArrayList<>());
            if (lines.size() < MAX_CONTEXT_LINES) {
                lines.add(text);
            }
        }
        return context;
    }

    /**
     * The report as MegaMek would show it to the viewer under double-blind: whole if they have seen its subject,
     * otherwise with its hidden values blanked, or not at all if the game suppresses such reports. It follows
     * {@code TWGameManager.filterReport}, which is private and marks the reports it filters as sent.
     *
     * @return the report as the viewer saw it, or {@code null} if they never saw it
     */
    static @Nullable Report asSeenBy(Report report, Game game, @Nullable Player viewer) {
        if ((viewer == null) || (report.type == Report.PUBLIC)
                  || !game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)) {
            return report;
        }
        Entity subject = findEntity(game, report.subject);
        if ((subject != null) && subject.isOffBoard()) {
            return report;
        }
        boolean hidden = ((subject != null) && !subject.hasSeenEntity(viewer))
                               || ((report.type == Report.PLAYER) && (viewer.getId() != report.player));
        if (!hidden) {
            return report;
        }
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_SUPPRESS_ALL_DB_MESSAGES)) {
            return null;
        }
        Report copy = new Report(report);
        for (int index = 0; index < copy.dataCount(); index++) {
            if (report.isValueObscured(index)) {
                copy.hideData(index);
            }
        }
        return copy;
    }

    /**
     * @return a report's text without markup, on one line, with anything hidden from the viewer marked
     *       {@value #UNSEEN}
     */
    static String plainText(Report report) {
        String text;
        try {
            text = report.text();
        } catch (RuntimeException ex) {
            return "";
        }
        return (text == null) ?
                     "" :
                     text.replace(Report.OBSCURED_STRING, UNSEEN).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
                           .strip();
    }
}
