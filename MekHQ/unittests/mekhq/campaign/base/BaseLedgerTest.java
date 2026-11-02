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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.xml.parsers.DocumentBuilderFactory;

import mekhq.campaign.finances.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/** Tests {@link BaseLedger}: what it lets a base spend, and that it survives a save. */
class BaseLedgerTest {
    private static final LocalDate DAY = LocalDate.of(3135, 2, 1);

    private BaseLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new BaseLedger();
    }

    @Test
    void newLedger_isBlankAndEmpty() {
        assertTrue(ledger.isBlank());
        assertTrue(ledger.getBalance().isZero());
        assertTrue(ledger.getEntries().isEmpty());
    }

    @Test
    void credit_addsToBalanceAndRecordsEntry() {
        ledger.credit(DAY, Money.of(1_000), "Deposit");

        assertEquals(Money.of(1_000), ledger.getBalance());
        assertEquals(1, ledger.getEntries().size());
        BaseLedger.Entry entry = ledger.getEntries().get(0);
        assertEquals("Deposit", entry.description());
        assertEquals(Money.of(1_000), entry.amount());
        assertEquals(Money.of(1_000), entry.balanceAfter());
        assertFalse(ledger.isBlank());
    }

    @Test
    void credit_ignoresAmountsThatAreNotPositive() {
        ledger.credit(DAY, Money.zero(), "Nothing");
        ledger.credit(DAY, Money.of(-5), "Negative");

        assertTrue(ledger.isBlank());
    }

    @Test
    void debit_spendsMoneyTheLedgerHolds() {
        ledger.credit(DAY, Money.of(1_000), "Deposit");

        assertTrue(ledger.debit(DAY, Money.of(400), "Build"));

        assertEquals(Money.of(600), ledger.getBalance());
        assertEquals(Money.of(-400), ledger.getEntries().get(1).amount());
        assertEquals(Money.of(600), ledger.getEntries().get(1).balanceAfter());
    }

    @Test
    void debit_refusesMoreThanTheLedgerHolds() {
        ledger.credit(DAY, Money.of(1_000), "Deposit");

        assertFalse(ledger.debit(DAY, Money.of(1_001), "Too much"));

        assertEquals(Money.of(1_000), ledger.getBalance());
        assertEquals(1, ledger.getEntries().size());
    }

    @Test
    void debit_allowsSpendingTheWholeBalance() {
        ledger.credit(DAY, Money.of(1_000), "Deposit");

        assertTrue(ledger.debit(DAY, Money.of(1_000), "All of it"));

        assertTrue(ledger.getBalance().isZero());
    }

    @Test
    void debit_refusesAmountsThatAreNotPositive() {
        ledger.credit(DAY, Money.of(1_000), "Deposit");

        assertFalse(ledger.debit(DAY, Money.zero(), "Nothing"));
        assertFalse(ledger.debit(DAY, Money.of(-5), "Negative"));

        assertEquals(Money.of(1_000), ledger.getBalance());
    }

    @Test
    void charge_canTakeTheBalanceBelowZero() {
        ledger.credit(DAY, Money.of(100), "Deposit");

        ledger.charge(DAY, Money.of(250), "Upkeep");

        assertEquals(Money.of(-150), ledger.getBalance());
        assertFalse(ledger.getBalance().isPositiveOrZero());
    }

    @Test
    void canAfford_comparesAgainstTheBalance() {
        ledger.credit(DAY, Money.of(500), "Deposit");

        assertTrue(ledger.canAfford(Money.of(500)));
        assertFalse(ledger.canAfford(Money.of(501)));
    }

    @Test
    void entries_dropTheOldestPastTheLimit() {
        for (int i = 1; i <= BaseLedger.MAX_ENTRIES + 5; i++) {
            ledger.credit(DAY, Money.of(i), "Deposit " + i);
        }

        assertEquals(BaseLedger.MAX_ENTRIES, ledger.getEntries().size());
        assertEquals("Deposit 6", ledger.getEntries().get(0).description());
        assertEquals("Deposit " + (BaseLedger.MAX_ENTRIES + 5),
              ledger.getEntries().get(BaseLedger.MAX_ENTRIES - 1).description());
    }

    @Test
    void xmlRoundTrip_keepsBalanceAndEntries() throws Exception {
        ledger.credit(DAY, Money.of(1_500_000), "Transfer from the main force");
        ledger.debit(DAY.plusDays(3), Money.of(500_000), "Build Garage, level 1");
        ledger.charge(DAY.plusDays(40), Money.of(5_000), "Monthly facility upkeep");

        BaseLedger loaded = BaseLedger.generateInstanceFromXML(write(ledger));

        assertEquals(ledger.getBalance(), loaded.getBalance());
        assertEquals(ledger.getEntries(), loaded.getEntries());
    }

    @Test
    void xmlRoundTrip_keepsANegativeBalanceAndDescriptionsWithMarkup() throws Exception {
        ledger.charge(DAY, Money.of(2_500), "Upkeep <March> & fees");

        BaseLedger loaded = BaseLedger.generateInstanceFromXML(write(ledger));

        assertEquals(Money.of(-2_500), loaded.getBalance());
        assertEquals("Upkeep <March> & fees", loaded.getEntries().get(0).description());
    }

    private static Node write(BaseLedger ledger) throws Exception {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            ledger.writeToXML(writer, 0);
        }
        Document document = DocumentBuilderFactory.newInstance()
                                  .newDocumentBuilder()
                                  .parse(new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)));
        return document.getDocumentElement();
    }
}
