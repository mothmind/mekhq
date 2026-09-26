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

import megamek.common.Player;
import megamek.common.annotations.Nullable;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;

/**
 * Who hears a pilot, and what the pilot may know when choosing what to say. Normally everyone hears and the pilot
 * knows the whole battle. Under double-blind a line must not tell anyone what their side cannot see, so it is written
 * from the point of view of a human who will hear it:
 * <ul>
 *     <li>a pilot on a side with a human player is heard by their own side, and knows what that side knows;</li>
 *     <li>a pilot on a side of bots is heard by the humans who can see them right now, and knows only what those
 *     humans know. If no human can see them, nobody hears them and the line is never written.</li>
 * </ul>
 * Players allowed to see everything hear every line. Either way a pilot answers only chatter their own side heard, so
 * an enemy never replies to the players' own comms.
 *
 * @param listeners the players who hear the line, or {@code null} for everyone
 * @param viewer    the human whose knowledge the line may draw on, or {@code null} for the whole battle
 * @param side      the player the speaker fights for, or {@code null} when nothing is hidden
 */
public record ChatAudience(@Nullable List<Player> listeners, @Nullable Player viewer, @Nullable Player side) {
    static final ChatAudience EVERYONE = new ChatAudience(null, null, null);

    public static ChatAudience of(Game game, Entity speaker) {
        if (!game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)) {
            return EVERYONE;
        }
        Player owner = speaker.getOwner();
        Player viewer = null;
        for (Player player : game.getPlayersList()) {
            if (isHuman(player) && sameSide(player, owner) && ((viewer == null) || player.equals(owner))) {
                viewer = player;
            }
        }
        boolean humanSide = viewer != null;

        List<Player> listeners = new ArrayList<>();
        for (Player player : game.getPlayersList()) {
            if (sameSide(player, owner) || player.canIgnoreDoubleBlind()) {
                listeners.add(player);
            } else if (!humanSide && isHuman(player) && canSeeNow(speaker, player)) {
                listeners.add(player);
                if (viewer == null) {
                    viewer = player;
                }
            }
        }
        return new ChatAudience(listeners, viewer, owner);
    }

    /**
     * @return whether any human will hear the line, making it worth writing
     */
    public boolean isHeard() {
        return (listeners == null) || (viewer != null);
    }

    /**
     * @return whether both the viewer and the speaker's side heard an earlier line, so it may be part of what this one
     *       draws on
     */
    public boolean heard(PilotJournal.Entry entry) {
        if ((viewer == null) || (entry.heardBy() == null)) {
            return true;
        }
        return entry.heardBy().contains(viewer.getId()) && ((side == null) || entry.heardBy().contains(side.getId()));
    }

    /**
     * @return the ids of the players who hear the line, for the journal, or {@code null} for everyone
     */
    public @Nullable List<Integer> listenerIds() {
        return (listeners == null) ? null : listeners.stream().map(Player::getId).toList();
    }

    private static boolean isHuman(Player player) {
        return !player.isBot() && !player.isObserver();
    }

    private static boolean sameSide(Player player, @Nullable Player owner) {
        return (owner != null) && !player.isEnemyOf(owner);
    }

    private static boolean canSeeNow(Entity speaker, Player player) {
        return (speaker.getWhoCanSee() != null) && speaker.getWhoCanSee().contains(player);
    }
}
