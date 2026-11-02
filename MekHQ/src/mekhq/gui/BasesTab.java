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
package mekhq.gui;

import static mekhq.utilities.MHQInternationalization.getFormattedTextAt;
import static mekhq.utilities.MHQInternationalization.getTextAt;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import jakarta.annotation.Nullable;
import megamek.client.ui.util.UIUtil;
import megamek.common.event.Subscribe;
import megamek.common.ui.FastJScrollPane;
import mekhq.campaign.Campaign;
import mekhq.campaign.base.BaseFacility;
import mekhq.campaign.base.PlayerBase;
import mekhq.campaign.events.LocationEvent;
import mekhq.gui.baseComponents.roundedComponents.RoundedLineBorder;
import mekhq.gui.dialog.BaseSettingsDialog;
import mekhq.gui.enums.MHQTabType;
import mekhq.gui.model.LocationDisplay;
import mekhq.gui.view.BaseDetailPanel;

/**
 * The bases the campaign manages: a list of every {@link PlayerBase} with its people, units and funds, and beside it
 * the selected base's own ledger and facilities (see {@link BaseDetailPanel}).
 *
 * <p>Moving people, units and spare parts to and from a base is still done from the Personnel, Hangar and Warehouse
 * tabs, through the Send To menu; the Locations tab inside Navigation still lists every location, bases or not.</p>
 */
public class BasesTab extends CampaignGuiTab {
    private static final String RESOURCE_BUNDLE = "mekhq.resources.Bases";
    private static final int DETAIL_WIDTH = UIUtil.scaleForGUI(460);
    /** Name, type, system, people, units, funds, facilities. */
    private static final int[] COLUMN_WIDTHS = { 150, 100, 100, 60, 60, 140, 240 };

    private BasesTableModel model;
    private JTable table;
    private FastJScrollPane detailScrollPane;
    private JButton configureButton;
    private final ActionScheduler refreshScheduler = new ActionScheduler(this::refreshAll);

    public BasesTab(CampaignGUI gui, String tabName) {
        super(gui, tabName);
    }

    @Override
    public void initTab() {
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton newButton = new JButton(getTextAt(RESOURCE_BUNDLE, "tab.toolbar.newBase"));
        newButton.addActionListener(event -> new BaseSettingsDialog(getFrame(), getCampaign()).setVisible(true));
        configureButton = new JButton(getTextAt(RESOURCE_BUNDLE, "tab.toolbar.configureBase"));
        configureButton.setEnabled(false);
        configureButton.addActionListener(event -> {
            PlayerBase selected = getSelectedBase();
            if (selected != null) {
                new BaseSettingsDialog(getFrame(), getCampaign(), selected).setVisible(true);
            }
        });
        toolbar.add(newButton);
        toolbar.add(configureButton);

        model = new BasesTableModel();
        table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        for (int column = 0; column < COLUMN_WIDTHS.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(COLUMN_WIDTHS[column]);
        }
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelectedBase();
            }
        });

        detailScrollPane = new FastJScrollPane();
        detailScrollPane.setBorder(RoundedLineBorder.createRoundedLineBorder());
        detailScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScrollPane.setPreferredSize(new Dimension(DETAIL_WIDTH, 600));
        detailScrollPane.setMinimumSize(new Dimension((int) (DETAIL_WIDTH * 0.9), 300));
        showNoSelection();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new FastJScrollPane(table),
              detailScrollPane);
        splitPane.setOneTouchExpandable(true);
        splitPane.setResizeWeight(1.0);

        add(toolbar, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }

    @Override
    public MHQTabType tabType() {
        return MHQTabType.BASES;
    }

    @Override
    public void refreshAll() {
        Campaign campaign = getCampaign();
        if (campaign == null) {
            return;
        }
        UUID selectedId = (getSelectedBase() == null) ? null : getSelectedBase().getId();
        model.setData(campaign);
        if (selectedId != null) {
            selectBase(selectedId);
        }
        showSelectedBase();
    }

    @Subscribe
    public void handle(LocationEvent event) {
        refreshScheduler.schedule();
    }

    private @Nullable PlayerBase getSelectedBase() {
        int row = table.getSelectedRow();
        return (row < 0) ? null : model.getBase(table.convertRowIndexToModel(row));
    }

    private void selectBase(UUID id) {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (model.getBase(row).getId().equals(id)) {
                int viewRow = table.convertRowIndexToView(row);
                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                return;
            }
        }
    }

    private void showSelectedBase() {
        PlayerBase selected = getSelectedBase();
        configureButton.setEnabled(selected != null);
        if (selected == null) {
            showNoSelection();
            return;
        }
        int scrollPosition = detailScrollPane.getVerticalScrollBar().getValue();
        detailScrollPane.setViewportView(new BaseDetailPanel(getCampaign(), selected, this::refreshAll));
        SwingUtilities.invokeLater(() -> detailScrollPane.getVerticalScrollBar().setValue(scrollPosition));
    }

    private void showNoSelection() {
        detailScrollPane.setViewportView(new JLabel(getTextAt(RESOURCE_BUNDLE, "tab.noSelection"),
              SwingConstants.CENTER));
    }

    /** One row for each {@link PlayerBase}, by name. */
    private static final class BasesTableModel extends AbstractTableModel {
        private static final String[] COLUMN_KEYS = { "tab.column.name", "tab.column.type", "tab.column.system",
                                                      "tab.column.people", "tab.column.units", "tab.column.funds",
                                                      "tab.column.facilities" };

        private List<PlayerBase> bases = List.of();
        private Campaign campaign;

        void setData(Campaign campaign) {
            this.campaign = campaign;
            List<PlayerBase> sorted = new ArrayList<>(campaign.getCampaignLocationManager().getPlayerBases());
            sorted.sort(Comparator.comparing(PlayerBase::getDisplayName, String.CASE_INSENSITIVE_ORDER));
            bases = sorted;
            fireTableDataChanged();
        }

        PlayerBase getBase(int row) {
            return bases.get(row);
        }

        @Override
        public int getRowCount() {
            return bases.size();
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
        public Class<?> getColumnClass(int column) {
            return ((column == 3) || (column == 4)) ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            PlayerBase base = bases.get(row);
            LocalDate today = campaign.getLocalDate();
            return switch (column) {
                case 0 -> base.getDisplayName();
                case 1 -> (base.getDisplayType() == null) ? "" : base.getDisplayType();
                case 2 -> LocationDisplay.getLocationSystem(base, today, campaign);
                case 3 -> base.getBasePersonnel().size();
                case 4 -> base.getBaseHangar().getUnits().size();
                case 5 -> base.getLedger().getBalance().toAmountAndSymbolString();
                default -> describeFacilities(base);
            };
        }

        private static String describeFacilities(PlayerBase base) {
            List<String> parts = new ArrayList<>();
            for (BaseFacility facility : base.getFacilities()) {
                String name = facility.getType().getDisplayName();
                if (facility.getLevel() > 0) {
                    parts.add(getFormattedTextAt(RESOURCE_BUNDLE, "tab.facilities.level", name,
                          facility.getLevel()));
                } else if (facility.isUnderConstruction()) {
                    parts.add(getFormattedTextAt(RESOURCE_BUNDLE, "tab.facilities.building", name,
                          facility.getTargetLevel()));
                }
            }
            return parts.isEmpty() ? getTextAt(RESOURCE_BUNDLE, "tab.facilities.none") : String.join(", ", parts);
        }
    }
}
