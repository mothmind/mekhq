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
package mekhq.gui.view;

import static mekhq.utilities.MHQInternationalization.getFormattedTextAt;
import static mekhq.utilities.MHQInternationalization.getTextAt;
import static mekhq.utilities.ReportingUtilities.CLOSING_SPAN_TAG;
import static mekhq.utilities.ReportingUtilities.getNegativeColor;
import static mekhq.utilities.ReportingUtilities.spanOpeningWithCustomColor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import jakarta.annotation.Nullable;
import megamek.client.ui.util.UIUtil;
import mekhq.MekHQ;
import mekhq.campaign.Campaign;
import mekhq.campaign.base.BaseFacility;
import mekhq.campaign.base.BaseFacilityManager;
import mekhq.campaign.base.BaseFacilityManager.BuildResult;
import mekhq.campaign.base.BaseFacilityType;
import mekhq.campaign.base.BaseLedger;
import mekhq.campaign.base.PlayerBase;
import mekhq.campaign.finances.Money;
import mekhq.campaign.unit.Unit;
import mekhq.gui.baseComponents.JScrollablePanel;

/**
 * Detail panel for one {@link PlayerBase} in the Bases tab: where it is, the money in its own ledger with buttons to
 * move C-bills to and from the main force, and its facilities with their effects and a button to build or upgrade
 * each one.
 *
 * <p>The panel is built for one moment in the campaign. After anything that changes the base it calls
 * {@code onChanged} and the tab builds a fresh one.</p>
 */
public class BaseDetailPanel extends JScrollablePanel {
    private static final String RESOURCE_BUNDLE = "mekhq.resources.Bases";
    private static final int LEDGER_ROWS_SHOWN = 6;
    /** Date, description, amount, balance. The description gets the most room. */
    private static final int[] LEDGER_COLUMN_WIDTHS = { 80, 170, 105, 105 };
    private static final int GAP = 8;
    /** How wide, in pixels, a line of text in the panel can be before it wraps. */
    private static final int TEXT_WIDTH = UIUtil.scaleForGUI(370);

    private final Campaign campaign;
    private final PlayerBase base;
    private final Runnable onChanged;

    /**
     * @param campaign  the campaign the base belongs to
     * @param base      the base to show
     * @param onChanged called after the base's money or facilities have changed, so the tab can refresh itself
     */
    public BaseDetailPanel(Campaign campaign, PlayerBase base, Runnable onChanged) {
        this.campaign = campaign;
        this.base = base;
        this.onChanged = onChanged;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));
        addSection(new LocationPlacePanel(base, campaign));
        addSection(buildFundsSection());
        addSection(buildFacilitiesSection());
        add(Box.createVerticalGlue());
    }

    private void addSection(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(section);
        add(Box.createRigidArea(new Dimension(0, GAP)));
    }

    // region Funds

    private JPanel buildFundsSection() {
        JPanel section = titledSection("detail.funds.title");

        section.add(leftAligned(wrappedLabel(describeBalance())));
        section.add(leftAligned(new JLabel(getFormattedTextAt(RESOURCE_BUNDLE, "detail.funds.mainForce",
              campaign.getPlayerForce().getFinances().getBalance().toAmountAndSymbolString()))));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, GAP / 2));
        JButton depositButton = new JButton(getTextAt(RESOURCE_BUNDLE, "detail.funds.deposit"));
        depositButton.addActionListener(event -> transfer(true));
        JButton withdrawButton = new JButton(getTextAt(RESOURCE_BUNDLE, "detail.funds.withdraw"));
        withdrawButton.addActionListener(event -> transfer(false));
        withdrawButton.setEnabled(base.getLedger().getBalance().isPositive());
        buttons.add(depositButton);
        buttons.add(Box.createRigidArea(new Dimension(GAP, 0)));
        buttons.add(withdrawButton);
        section.add(leftAligned(buttons));

        if (base.getLedger().getEntries().isEmpty()) {
            section.add(leftAligned(new JLabel(getTextAt(RESOURCE_BUNDLE, "detail.funds.noEntries"))));
        } else {
            JTable table = new JTable(new LedgerTableModel(base.getLedger().getEntries()));
            table.setFillsViewportHeight(true);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            for (int column = 0; column < LEDGER_COLUMN_WIDTHS.length; column++) {
                table.getColumnModel().getColumn(column).setPreferredWidth(LEDGER_COLUMN_WIDTHS[column]);
            }
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(0, table.getRowHeight() * LEDGER_ROWS_SHOWN));
            section.add(leftAligned(scrollPane));
        }
        return section;
    }

    private String describeBalance() {
        Money balance = base.getLedger().getBalance();
        String text = getFormattedTextAt(RESOURCE_BUNDLE, "detail.funds.balance", balance.toAmountAndSymbolString());
        return balance.isPositiveOrZero() ? text : asNegative(text);
    }

    private void transfer(boolean deposit) {
        String baseName = base.getDisplayName();
        String prompt = getFormattedTextAt(RESOURCE_BUNDLE,
              deposit ? "detail.funds.depositPrompt" : "detail.funds.withdrawPrompt", baseName);
        String input = JOptionPane.showInputDialog(this, prompt, getTextAt(RESOURCE_BUNDLE, "detail.funds.dialogTitle"),
              JOptionPane.QUESTION_MESSAGE);
        if (input == null) {
            return;
        }
        Money amount = parseAmount(input);
        if (amount == null) {
            showMessage(getTextAt(RESOURCE_BUNDLE, "detail.funds.invalidAmount"));
            return;
        }
        boolean moved = deposit
              ? BaseFacilityManager.depositFromMainForce(campaign, base, amount)
              : BaseFacilityManager.withdrawToMainForce(campaign, base, amount);
        if (!moved) {
            showMessage(deposit
                  ? getFormattedTextAt(RESOURCE_BUNDLE, "detail.funds.depositFailed", amount.toAmountAndSymbolString())
                  : getFormattedTextAt(RESOURCE_BUNDLE, "detail.funds.withdrawFailed", baseName,
                        amount.toAmountAndSymbolString()));
        }
        onChanged.run();
    }

    /**
     * @return the whole number of C-bills typed, or {@code null} if the text is not a whole number above zero. Commas,
     *       spaces and underscores between the digits are ignored.
     */
    static @Nullable Money parseAmount(String text) {
        String trimmed = text.trim();
        if (!trimmed.matches("[0-9][0-9,_ ]*")) {
            return null;
        }
        String digits = trimmed.replaceAll("[,_ ]", "");
        if (digits.length() > 15) {
            return null;
        }
        long value = Long.parseLong(digits);
        return (value > 0) ? Money.of(value) : null;
    }

    // endregion Funds

    // region Facilities

    private JPanel buildFacilitiesSection() {
        JPanel section = titledSection("detail.facilities.title");
        for (BaseFacility facility : base.getFacilities()) {
            JPanel panel = buildFacilityPanel(facility);
            section.add(leftAligned(panel));
            section.add(Box.createRigidArea(new Dimension(0, GAP)));
        }
        section.add(leftAligned(wrappedLabel(getFormattedTextAt(RESOURCE_BUNDLE, "detail.facilities.upkeepTotal",
              BaseFacilityManager.getMonthlyUpkeep(base).toAmountAndSymbolString()))));
        section.add(leftAligned(wrappedLabel(getTextAt(RESOURCE_BUNDLE, "detail.facilities.note"))));
        return section;
    }

    private JPanel buildFacilityPanel(BaseFacility facility) {
        BaseFacilityType type = facility.getType();
        int level = facility.getLevel();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEtchedBorder(),
              BorderFactory.createEmptyBorder(GAP / 2, GAP, GAP / 2, GAP)));

        String header = (level > 0)
              ? getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.header", type.getDisplayName(), level,
                    BaseFacilityType.MAX_LEVEL)
              : getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.notBuilt", type.getDisplayName());
        JLabel headerLabel = new JLabel(header);
        headerLabel.setFont(headerLabel.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(leftAligned(headerLabel));
        panel.add(leftAligned(wrappedLabel(type.getDescription())));
        panel.add(leftAligned(wrappedLabel(describeEffect(type, level))));

        if (facility.isUnderConstruction()) {
            panel.add(leftAligned(wrappedLabel(getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.underConstruction",
                  facility.getTargetLevel(),
                  MekHQ.getMHQOptions().getDisplayFormattedDate(facility.getCompletionDate())))));
        } else if (facility.isAtMaxLevel()) {
            panel.add(leftAligned(new JLabel(getTextAt(RESOURCE_BUNDLE, "detail.facility.maxLevel"))));
        } else {
            panel.add(leftAligned(wrappedLabel(describeNextLevel(type, level + 1))));
            panel.add(leftAligned(buildBuildButton(facility)));
        }
        return panel;
    }

    private String describeEffect(BaseFacilityType type, int level) {
        return switch (type) {
            case GARAGE -> (level == 0)
                  ? getTextAt(RESOURCE_BUNDLE, "detail.facility.garage.none")
                  : getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.garage.built",
                        Unit.getSiteName(type.getRepairSiteFloor(level)));
            case WAREHOUSE -> {
                double used = BaseFacilityManager.getWarehouseTonsUsed(base);
                String usedText = String.format("%.1f", used);
                if (level == 0) {
                    yield getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.warehouse.none", usedText);
                }
                int capacity = type.getCapacity(level);
                String text = getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.warehouse.built", capacity,
                      usedText);
                yield (used > capacity) ? text + ' ' + asNegative(getTextAt(RESOURCE_BUNDLE,
                      "detail.facility.overCapacity")) : text;
            }
            case BARRACKS -> {
                int stationed = BaseFacilityManager.getPersonnelCount(base);
                if (level == 0) {
                    yield getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.barracks.none", stationed);
                }
                int capacity = type.getCapacity(level);
                String text = getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.barracks.built", capacity,
                      stationed);
                yield (stationed > capacity) ? text + ' ' + asNegative(getTextAt(RESOURCE_BUNDLE,
                      "detail.facility.overCapacity")) : text;
            }
        };
    }

    private static String describeNextLevel(BaseFacilityType type, int nextLevel) {
        return getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.nextLevel",
              type.getBuildCost(nextLevel).toAmountAndSymbolString(), type.getBuildDays(nextLevel),
              type.getMonthlyUpkeep(nextLevel).toAmountAndSymbolString());
    }

    private JComponent buildBuildButton(BaseFacility facility) {
        BaseFacilityType type = facility.getType();
        int nextLevel = facility.getLevel() + 1;
        Money cost = type.getBuildCost(nextLevel);
        JButton button = new JButton((facility.getLevel() == 0)
              ? getTextAt(RESOURCE_BUNDLE, "detail.facility.build")
              : getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.upgrade", nextLevel));
        boolean affordable = base.getLedger().canAfford(cost);
        button.setEnabled(affordable);
        if (!affordable) {
            button.setToolTipText(getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.cantAfford",
                  cost.toAmountAndSymbolString()));
        }
        button.addActionListener(event -> {
            BuildResult result = BaseFacilityManager.startConstruction(campaign.getLocalDate(), base, type);
            if (result == BuildResult.INSUFFICIENT_FUNDS) {
                showMessage(getFormattedTextAt(RESOURCE_BUNDLE, "detail.facility.cantAfford",
                      cost.toAmountAndSymbolString()));
            }
            onChanged.run();
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, GAP / 2));
        row.add(button);
        return row;
    }

    // endregion Facilities

    // region Helpers

    private JPanel titledSection(String titleKey) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(getTextAt(RESOURCE_BUNDLE, titleKey)));
        return section;
    }

    private static <T extends JComponent> T leftAligned(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static String asNegative(String text) {
        return spanOpeningWithCustomColor(getNegativeColor()) + text + CLOSING_SPAN_TAG;
    }

    /** @return the text as HTML held to {@link #TEXT_WIDTH}, so a long line wraps instead of running off the panel */
    private static String asHtml(String text) {
        return "<html><div style='width:" + TEXT_WIDTH + "px'>" + text + "</div></html>";
    }

    private static JLabel wrappedLabel(String text) {
        return new JLabel(asHtml(text));
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, getTextAt(RESOURCE_BUNDLE, "detail.funds.dialogTitle"),
              JOptionPane.WARNING_MESSAGE);
    }

    // endregion Helpers

    /** The ledger's entries, newest first. */
    private static final class LedgerTableModel extends AbstractTableModel {
        private static final String[] COLUMN_KEYS = { "detail.ledger.column.date", "detail.ledger.column.description",
                                                      "detail.ledger.column.amount", "detail.ledger.column.balance" };

        private final List<BaseLedger.Entry> entries;

        LedgerTableModel(List<BaseLedger.Entry> oldestFirst) {
            entries = new ArrayList<>(oldestFirst);
            Collections.reverse(entries);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_KEYS.length;
        }

        @Override
        public String getColumnName(int column) {
            return getTextAt(RESOURCE_BUNDLE, COLUMN_KEYS[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            BaseLedger.Entry entry = entries.get(row);
            return switch (column) {
                case 0 -> MekHQ.getMHQOptions().getDisplayFormattedDate(entry.date());
                case 1 -> entry.description();
                case 2 -> entry.amount().toAmountString();
                default -> entry.balanceAfter().toAmountString();
            };
        }
    }
}
