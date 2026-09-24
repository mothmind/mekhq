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
package mekhq.campaign.mission.utilities;

import megamek.common.compute.Compute;
import megamek.common.annotations.Nullable;
import mekhq.campaign.mission.contract.AbstractContract;
import mekhq.campaign.mission.contract.contractData.ContractMoraleLevel;
import mekhq.campaign.mission.scenarios.Scenario;
import mekhq.campaign.mission.scenarios.ScenarioStatus;

/**
 * Works out which side holds the orbital space over a battlefield, under the contested method.
 *
 * <p>Only one fleet can sit in a firing position over the same patch of ground, so the two sides compete for it
 * rather than each rolling in isolation. Both start a contract on even terms, with a margin left over for the ship
 * that is off station when the call comes. The enemy's share follows their morale up and back down again, and the
 * balance moves with the outcome of any space battle fought during the contract: win the void and the guns overhead
 * become yours, lose it and the enemy's grip tightens.</p>
 *
 * @author mothmind
 */
public class OrbitalControlCalculator {

    private OrbitalControlCalculator() {
    }

    /** Which side, if either, has a ship in a position to fire this scenario. */
    public enum OrbitalHolder {
        PLAYER, OPFOR, NEITHER
    }

    /**
     * The share each side starts a contract with. The twenty points the two of them leave between them are the
     * chance that neither can fire: a ship out of position, a closed firing window, orders that never arrived.
     */
    public static final int BASE_CHANCE = 40;

    /**
     * The most the enemy's morale can move their share, in either direction: twenty points to a force whose
     * campaign is going perfectly, and twenty off one that has been routed.
     */
    public static final int MAXIMUM_MORALE_MODIFIER = 20;

    /**
     * How far one space battle moves the balance. A win takes these points off the enemy and gives them to the
     * player; a loss does the reverse. Four decisive engagements are therefore enough to take orbit outright.
     */
    public static final int SPACE_BATTLE_SHIFT = 10;

    /**
     * The player's and the opposing force's percentage shares of the orbital space, out of one hundred. Whatever the
     * two leave between them is the chance that neither side can fire.
     *
     * @param playerChance The player's share
     * @param enemyChance  The opposing force's share
     */
    public record OrbitalControl(int playerChance, int enemyChance) {
        /** @return The share that belongs to neither side, never negative */
        public int neitherChance() {
            return Math.max(0, 100 - playerChance - enemyChance);
        }
    }

    /**
     * Works out how the orbital space over this contract is currently divided.
     *
     * <p>A contract that has not been fought over yet splits it evenly. From there the enemy's share moves with
     * their morale and shrinks with every space battle they have lost, and the player's share moves the opposite
     * way. Where the two would together claim more than the whole sky, the enemy's share gives way first: space the
     * player has won in battle is not handed back to a morale bonus.</p>
     *
     * @param contract The contract the scenario belongs to, or null when there is none to read
     *
     * @return The current division of the orbital space
     */
    public static OrbitalControl forContract(@Nullable AbstractContract contract) {
        if (contract == null) {
            return new OrbitalControl(BASE_CHANCE, BASE_CHANCE);
        }

        int shift = spaceBattleShift(contract);
        int playerChance = Math.clamp(BASE_CHANCE + shift, 0, 100);
        int enemyChance = Math.clamp(BASE_CHANCE + moraleModifier(contract.getMoraleLevel()) - shift,
              0,
              100 - playerChance);

        return new OrbitalControl(playerChance, enemyChance);
    }

    /**
     * Rolls for who holds orbit this scenario.
     *
     * @param control The current division of the orbital space
     *
     * @return The side that can fire, or {@link OrbitalHolder#NEITHER} when no one can
     */
    public static OrbitalHolder roll(OrbitalControl control) {
        int roll = Compute.randomInt(100);
        if (roll < control.playerChance()) {
            return OrbitalHolder.PLAYER;
        }
        if (roll < control.playerChance() + control.enemyChance()) {
            return OrbitalHolder.OPFOR;
        }
        return OrbitalHolder.NEITHER;
    }

    /**
     * Reads what the enemy's morale is worth to them in orbit.
     *
     * <p>Morale cuts both ways. A force whose campaign is going well presses its advantage overhead as well as on
     * the ground; one that is being beaten has other calls on its navy, and a routed force is not holding anything.
     * The modifier grows evenly with each step of morale either side of a stalemate, reaching its cap at
     * Overwhelming and its floor at Routed.</p>
     *
     * @param moraleLevel The opposing force's current morale
     *
     * @return The points this morale moves the enemy's share by, within plus or minus
     *       {@link #MAXIMUM_MORALE_MODIFIER}
     */
    public static int moraleModifier(@Nullable ContractMoraleLevel moraleLevel) {
        if (moraleLevel == null) {
            return 0;
        }

        int level = moraleLevel.getLevel();
        int modifier = Math.round((level * (float) MAXIMUM_MORALE_MODIFIER)
              / ContractMoraleLevel.MAXIMUM_MORALE_LEVEL);
        return Math.clamp(modifier, -MAXIMUM_MORALE_MODIFIER, MAXIMUM_MORALE_MODIFIER);
    }

    /**
     * Totals what the contract's space battles have done to the balance.
     *
     * <p>Every finished scenario fought in space counts, whether the player set it up themselves or it was generated
     * for them. A draw settles nothing and is passed over; a refused engagement or a fleet kept in being counts
     * against the player, since the enemy is left holding the space either way.</p>
     *
     * @param contract The contract whose scenarios are read
     *
     * @return The points to move from the enemy to the player, negative when the enemy is ahead
     */
    public static int spaceBattleShift(AbstractContract contract) {
        int shift = 0;

        for (Scenario scenario : contract.getScenarios()) {
            if (scenario.getBoardType() != Scenario.T_SPACE) {
                continue;
            }

            ScenarioStatus status = scenario.getStatus();
            if (status.isOverallVictory()) {
                shift += SPACE_BATTLE_SHIFT;
            } else if (status.isOverallDefeat()) {
                shift -= SPACE_BATTLE_SHIFT;
            }
        }

        return shift;
    }
}
