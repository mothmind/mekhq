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
package mekhq.campaign.base;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import megamek.logging.MMLogger;
import mekhq.campaign.finances.Money;
import mekhq.utilities.MHQXMLUtility;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The money a {@link PlayerBase} holds, kept apart from the campaign's own funds.
 *
 * <p>Money reaches the ledger only by a transfer from the main force (see
 * {@link BaseFacilityManager#depositFromMainForce}) and leaves it by a transfer back, by paying for construction, or
 * by upkeep. {@link #debit} refuses to spend more than the ledger holds; {@link #charge} is for upkeep that is owed
 * whether or not the base can pay it, so it can take the balance below zero.</p>
 *
 * <p>Only the most recent {@value #MAX_ENTRIES} entries are kept, for display.</p>
 */
public class BaseLedger {
    private static final MMLogger LOGGER = MMLogger.create(BaseLedger.class);

    /** How many entries are kept before the oldest drop off. */
    static final int MAX_ENTRIES = 100;

    /**
     * One line of the ledger.
     *
     * @param date         the day it happened
     * @param description  what it was for
     * @param amount       the change to the balance: positive for money in, negative for money out
     * @param balanceAfter the balance once it was applied
     */
    public record Entry(LocalDate date, String description, Money amount, Money balanceAfter) {}

    private Money balance = Money.zero();
    private final List<Entry> entries = new ArrayList<>();

    public Money getBalance() {
        return balance;
    }

    /** @return the entries, oldest first */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** @return {@code true} if the ledger holds no money and has no entries */
    public boolean isBlank() {
        return balance.isZero() && entries.isEmpty();
    }

    public boolean canAfford(Money amount) {
        return balance.isGreaterOrEqualThan(amount);
    }

    /** Adds money. Nothing happens for an amount that is not positive. */
    public void credit(LocalDate date, Money amount, String description) {
        if (!amount.isPositive()) {
            return;
        }
        apply(date, amount, description);
    }

    /**
     * Spends money the ledger holds.
     *
     * @return {@code false}, changing nothing, if the amount is not positive or is more than the ledger holds
     */
    public boolean debit(LocalDate date, Money amount, String description) {
        if (!amount.isPositive() || !canAfford(amount)) {
            return false;
        }
        apply(date, amount.multipliedBy(-1), description);
        return true;
    }

    /** Takes money the base owes, such as upkeep, even if that leaves the balance below zero. */
    public void charge(LocalDate date, Money amount, String description) {
        if (!amount.isPositive()) {
            return;
        }
        apply(date, amount.multipliedBy(-1), description);
    }

    private void apply(LocalDate date, Money amount, String description) {
        balance = balance.plus(amount);
        entries.add(new Entry(date, description, amount, balance));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public void writeToXML(PrintWriter pw, int indent) {
        MHQXMLUtility.writeSimpleXMLOpenTag(pw, indent++, "baseLedger");
        MHQXMLUtility.writeSimpleXMLTag(pw, indent, "balance", balance);
        for (Entry entry : entries) {
            MHQXMLUtility.writeSimpleXMLOpenTag(pw, indent++, "entry");
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "date", entry.date());
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "description", entry.description());
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "amount", entry.amount());
            MHQXMLUtility.writeSimpleXMLTag(pw, indent, "balanceAfter", entry.balanceAfter());
            MHQXMLUtility.writeSimpleXMLCloseTag(pw, --indent, "entry");
        }
        MHQXMLUtility.writeSimpleXMLCloseTag(pw, --indent, "baseLedger");
    }

    /** Reads a {@code baseLedger} node. A damaged entry is skipped; a damaged balance leaves the ledger at zero. */
    public static BaseLedger generateInstanceFromXML(Node wn) {
        BaseLedger ledger = new BaseLedger();
        NodeList nodes = wn.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            try {
                if (node.getNodeName().equalsIgnoreCase("balance")) {
                    ledger.balance = Money.fromXmlString(node.getTextContent().trim());
                } else if (node.getNodeName().equalsIgnoreCase("entry")) {
                    Entry entry = readEntry(node);
                    if (entry != null) {
                        ledger.entries.add(entry);
                    }
                }
            } catch (Exception ex) {
                LOGGER.error(ex, "Could not read a base ledger node '{}'", node.getNodeName());
            }
        }
        return ledger;
    }

    private static Entry readEntry(Node wn) {
        LocalDate date = null;
        String description = "";
        Money amount = null;
        Money balanceAfter = null;
        NodeList nodes = wn.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String text = node.getTextContent().trim();
            switch (node.getNodeName().toLowerCase()) {
                case "date" -> date = MHQXMLUtility.parseDate(text);
                case "description" -> description = MHQXMLUtility.unEscape(text);
                case "amount" -> amount = Money.fromXmlString(text);
                case "balanceafter" -> balanceAfter = Money.fromXmlString(text);
                default -> {
                }
            }
        }
        if ((date == null) || (amount == null) || (balanceAfter == null)) {
            LOGGER.warn("Skipping a base ledger entry that is missing its date or amounts");
            return null;
        }
        return new Entry(date, description, amount, balanceAfter);
    }
}
