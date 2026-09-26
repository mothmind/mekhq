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

import java.util.List;

import mekhq.pilotChatter.BattleEventDetector.Trigger;

/**
 * Writes the request for one line of chatter: who the pilot is, what just happened to them, what they have said
 * before, and what the battlefield has been saying.
 */
public final class ChatterPrompt {
    static final int OWN_LINES = 6;
    static final int BATTLE_LINES = 8;

    static final String SYSTEM = """
          You voice the pilots in a live BattleTech battle that a group of friends is playing. Each time you are \
          asked, write the one line a single pilot says over comms right now.
          - One line, at most 20 words. No quotation marks, narration, stage directions, emojis or hashtags.
          - Speak as that pilot: their personality, history and side. Stay inside the BattleTech universe.
          - React to what just happened. Callbacks to earlier lines are welcome; repeating them is not.
          - Chatter marked [enemy] comes from the other side: you may taunt them, never speak for them or use their \
          slogans.
          - Never mention map hexes, coordinates, headings, or where anyone is or is going.
          - Anything marked [unseen] is hidden from the pilot's side: never name it, describe it or guess at it.
          - Salty soldier talk is fine.""";

    private ChatterPrompt() {}

    static String build(PilotDossier dossier, int team, Trigger trigger, boolean pilotDead, int round,
          List<PilotJournal.Entry> ownLines, List<PilotJournal.Entry> battleLines) {
        return build(dossier, "", "", team, trigger, pilotDead, round, ownLines, battleLines);
    }

    /**
     * @param dossier     the speaker
     * @param briefing    what the speaker knows beyond their own unit, as {@link BattleBriefing} writes it; may be
     *                    empty
     * @param recall      a request to draw on the company's story, from {@link BattleBriefing#recall}; may be empty
     * @param team        the speaker's team, to tell their side's chatter from the enemy's
     * @param trigger     what happened to them
     * @param pilotDead   whether the pilot is dead, making this their last words
     * @param round       the game round
     * @param ownLines    the speaker's recent lines, oldest first
     * @param battleLines the battle's recent lines, oldest first
     *
     * @return the request for the model
     */
    static String build(PilotDossier dossier, String briefing, String recall, int team, Trigger trigger,
          boolean pilotDead, int round, List<PilotJournal.Entry> ownLines, List<PilotJournal.Entry> battleLines) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("THE PILOT\n").append(dossier.sheet()).append("\n\n");
        prompt.append(briefing);

        prompt.append("WHAT JUST HAPPENED (round ").append(round).append(")\n");
        prompt.append("- The pilot ").append(trigger.event().description()).append('\n');
        if (!trigger.detail().isBlank()) {
            prompt.append("- ").append(trigger.detail()).append('\n');
        }
        for (String line : trigger.context()) {
            prompt.append("- ").append(line).append('\n');
        }
        if (!recall.isBlank()) {
            prompt.append("- ").append(recall).append('\n');
        }
        if (trigger.event() == ChatterEvent.HEADSHOT) {
            prompt.append("- They never saw it coming, so this is no farewell: write what they were in the middle of ")
                  .append("saying when it hit. It will be cut off.\n");
        } else if (pilotDead) {
            prompt.append("- The pilot did not survive. These are their last words.\n");
        }

        if (!ownLines.isEmpty()) {
            prompt.append("\nWHAT THIS PILOT HAS SAID BEFORE\n");
            for (PilotJournal.Entry entry : ownLines) {
                prompt.append("- ").append(entry.line()).append('\n');
            }
        }

        if (!battleLines.isEmpty()) {
            prompt.append("\nRECENT CHATTER IN THIS BATTLE\n");
            for (PilotJournal.Entry entry : battleLines) {
                prompt.append("- ").append(sideLabel(entry.team(), team)).append(speakerName(entry)).append(": ")
                      .append(entry.line()).append('\n');
            }
        }

        prompt.append("\nWrite the pilot's line now.");
        return prompt.toString();
    }

    private static String speakerName(PilotJournal.Entry entry) {
        String pronouns = entry.pronouns();
        if ((pronouns == null) || pronouns.isBlank()) {
            return entry.speaker();
        }
        String speaker = entry.speaker();
        return speaker.endsWith(")") ?
                     speaker.substring(0, speaker.length() - 1) + ", " + pronouns + ")" :
                     speaker + " (" + pronouns + ")";
    }

    private static String sideLabel(int speakerTeam, int listenerTeam) {
        if ((speakerTeam <= 0) || (listenerTeam <= 0)) {
            return "";
        }
        return (speakerTeam == listenerTeam) ? "[your side] " : "[enemy] ";
    }
}
