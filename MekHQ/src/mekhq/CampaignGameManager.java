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
package mekhq;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import megamek.common.game.Game;
import megamek.common.game.IGame;
import megamek.server.totalWarfare.TWGameManager;

/**
 * The game manager MekHQ hosts battles with. When the server swaps in a different game, as a player's {@code /load}
 * does mid-session, it tells whoever asked, so anything MekHQ had hooked onto the old game can follow the battle to
 * the new one. Game listeners are not saved with a game, so without this they would be left behind.
 */
public class CampaignGameManager extends TWGameManager {
    private final List<Consumer<Game>> gameReplacedListeners = new CopyOnWriteArrayList<>();

    /**
     * @param listener called with the new game each time the server swaps one in
     */
    public void addGameReplacedListener(Consumer<Game> listener) {
        gameReplacedListeners.add(listener);
    }

    @Override
    public void setGame(IGame game) {
        super.setGame(game);
        if (game instanceof Game newGame) {
            for (Consumer<Game> listener : gameReplacedListeners) {
                listener.accept(newGame);
            }
        }
    }
}
