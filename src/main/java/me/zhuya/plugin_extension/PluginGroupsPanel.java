package me.zhuya.plugin_extension;

import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.PluginLoader;
import net.mcreator.ui.component.JEmptyBox;
import net.mcreator.ui.component.util.ComponentUtils;
import net.mcreator.ui.dialogs.preferences.PreferencesDialog;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.init.UIRES;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.util.DesktopUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class PluginGroupsPanel extends JPanel implements Scrollable {
	private static final String KEY_UNGROUPED = "__ungrouped__";
	private static final String KEY_BUILTIN = "__builtin__";
	private static final int GROUP_COLUMN_WIDTH = 150;
	private static final String HOOKED_PROPERTY = "plugin_extension.hooked";
	private final PreferencesDialog dialog;
	private final PluginGroupsConfig config = PluginGroupsConfig.get();
	private final Map<String, JCheckBox> pluginBoxes = new HashMap<>();
	private final Map<String, JCheckBox> groupBoxes = new HashMap<>();
	private final Map<String, JLabel> groupLabels = new HashMap<>();
	private final Map<String, JPanel> groupRows = new HashMap<>();
	private final Map<String, JLabel> detailCounters = new HashMap<>();
	private final JPanel groupColumn = new JPanel();
	private final JPanel detail = new JPanel();
	private final JPanel detailList = new JPanel();
	private final List<GroupEntry> entries = new ArrayList<>();
	private List<Plugin> loadedPlugins = new ArrayList<>();
	private List<PluginToggleService.DisabledPlugin> parkedPlugins = new ArrayList<>();
	private List<Row> loadedRows = List.of();
	private List<Row> parkedRows = List.of();
	private final Map<String, List<Row>> rowsById = new LinkedHashMap<>();
	private List<Row> builtinRows = List.of();
	private List<Row> ungroupedRows = List.of();
	private String selectedKey;
	private boolean reloadQueued;
	private Filter filter = Filter.ALL;
	/** Available updates keyed by plugin id; replaced as a whole so readers never see a partial map. */
	private volatile Map<String, UpdateService.UpdateInfo> updates = Map.of();
	private boolean updateCheckRunning;

	private enum Filter {
		ALL, ENABLED, DISABLED
	}

	private record Row(String id, String name, String version, String author, String description, boolean loaded, boolean parked, boolean builtin, boolean manageable) {
		static Row of(Plugin plugin) {
			return new Row(plugin.getID(), plugin.getInfo().getName(), plugin.getPluginVersion(),
					plugin.getInfo().getAuthor(), plugin.getInfo().getDescription(), plugin.isLoaded(), false,
					plugin.isBuiltin(), PluginToggleService.isManageable(plugin));
		}
		static Row of(PluginToggleService.DisabledPlugin plugin) {
			return new Row(plugin.id(), plugin.name(), plugin.version(), plugin.author(), plugin.description(),
					false, true, false, true);
		}
	}

	private record GroupEntry(String key, String name, PluginGroupsConfig.PluginGroup group, boolean locked) {}

	public static void install(PreferencesDialog dialog) {
		JList<?> list = findPluginList(dialog);
		if (list == null) {
			return;
		}
		JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
		if (scrollPane == null) {
			return;
		}
		PluginGroupsConfig.invalidate();
		PluginGroupsPanel panel = new PluginGroupsPanel(dialog, list);
		scrollPane.setViewportView(panel);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		scrollPane.getViewport().setBackground(Theme.current().getBackgroundColor());
		hookSaveButtons(dialog, panel);
	}

	private PluginGroupsPanel(PreferencesDialog dialog, JList<?> replacedList) {
		this.dialog = dialog;
		setOpaque(false);
		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(2, 2, 2, 8));
		if (replacedList.getModel() instanceof DefaultListModel<?> model)
			model.addListDataListener(new ListDataListener() {
				@Override public void intervalAdded(ListDataEvent e) {
					queueReload();
				}
				@Override public void intervalRemoved(ListDataEvent e) {
					queueReload();
				}
				@Override public void contentsChanged(ListDataEvent e) {
					queueReload();
				}
			});
		groupColumn.setLayout(new BoxLayout(groupColumn, BoxLayout.Y_AXIS));
		groupColumn.setOpaque(false);
		detail.setLayout(new BorderLayout());
		detailList.setOpaque(false);
		detailList.setLayout(new BoxLayout(detailList, BoxLayout.Y_AXIS));
		detail.setOpaque(false);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollOf(groupColumn), scrollOf(detail));
		split.setDividerLocation(GROUP_COLUMN_WIDTH);
		split.setContinuousLayout(true);
		split.setBorder(null);
		split.setOpaque(false);
		add("North", createToolbar());
		add("Center", split);
		reload();
	}

	private void queueReload() {
		if (reloadQueued)
			return;
		reloadQueued = true;
		SwingUtilities.invokeLater(() -> {
			reloadQueued = false;
			reload();
		});
	}

	private void reload() {
		loadedPlugins = PluginLoader.INSTANCE.getPlugins().stream().sorted(Comparator.comparing(Plugin::isBuiltin)
						.thenComparing(Comparator.comparing(Plugin::getWeight).reversed())).toList();
		parkedPlugins = PluginToggleService.listDisabled();
		PluginToggleService.ensureDisabledFolder();
		config.disabled.removeIf(PluginToggleService::isSelf);
		pruneMissingPlugins();
		List<Row> loaded = new ArrayList<>();
		for (Plugin plugin : loadedPlugins) loaded.add(Row.of(plugin));
		loadedRows = loaded;
		List<Row> parked = new ArrayList<>();
		for (PluginToggleService.DisabledPlugin plugin : parkedPlugins) parked.add(Row.of(plugin));
		parkedRows = parked;
		rebuild();
	}

	private void pruneMissingPlugins() {
		Set<String> groupable = new HashSet<>();
		for (Plugin plugin : loadedPlugins)
			if (!plugin.isBuiltin()) groupable.add(plugin.getID());
		parkedPlugins.forEach(plugin -> groupable.add(plugin.id()));
		config.groups.forEach(group -> group.plugins.removeIf(id -> !groupable.contains(id)));
	}

	private void rebuild() {
		entries.clear();
		for (PluginGroupsConfig.PluginGroup group : config.groups) entries.add(new GroupEntry(group.id, group.name, group, false));
		entries.add(new GroupEntry(KEY_UNGROUPED, L10N.t("plugin_extension.ungrouped"), null, false));
		entries.add(new GroupEntry(KEY_BUILTIN, L10N.t("plugin_extension.builtin"), null, true));
		if (entries.stream().noneMatch(entry -> entry.key().equals(selectedKey))) selectedKey = entries.getFirst().key();
		rebuildIndex();
		buildGroupColumn();
		buildDetail();
		syncAll();
		revalidate();
		repaint();
	}

	private void rebuildIndex() {
		rowsById.clear();
		for (Row row : loadedRows)
			if (!row.builtin()) rowsById.computeIfAbsent(row.id(), key -> new ArrayList<>()).add(row);
		// A plugin that was parked during this session is still in PluginLoader's plugin list, so the same
		// id can show up here as both a loaded row and a parked row. Keep only the parked one: it is the
		// archive the user can actually act on.
		for (Row row : parkedRows) {
			List<Row> known = rowsById.get(row.id());
			if (known != null && !known.isEmpty()) {
				known.clear();
				known.add(row);
				continue;
			}
			rowsById.computeIfAbsent(row.id(), key -> new ArrayList<>()).add(row);
		}
		builtinRows = loadedRows.stream().filter(Row::builtin).toList();
		Set<String> assigned = new HashSet<>();
		config.groups.forEach(group -> assigned.addAll(group.plugins));
		Set<String> seen = new HashSet<>();
		List<Row> ungrouped = new ArrayList<>();
		for (Row row : loadedRows)
			if (!row.builtin() && !assigned.contains(row.id()) && seen.add(row.id())) ungrouped.add(row);
		for (Row row : parkedRows)
			if (!assigned.contains(row.id()) && seen.add(row.id())) ungrouped.add(row);
		ungroupedRows = ungrouped;
	}

	private GroupEntry selectedEntry() {
		for (GroupEntry entry : entries)
			if (entry.key().equals(selectedKey)) return entry;
		return entries.isEmpty() ? null : entries.getFirst();
	}

	private List<Row> rowsFor(GroupEntry entry) {
		if (entry.group() != null) {
			List<Row> rows = new ArrayList<>();
			for (String id : entry.group().plugins) {
				List<Row> matches = rowsById.get(id);
				if (matches != null) rows.addAll(matches);
			}
			return rows;
		}
		return KEY_BUILTIN.equals(entry.key()) ? builtinRows : ungroupedRows;
	}

	private JComponent createToolbar() {
		JPanel toolbar = stretchable();
		toolbar.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
		toolbar.setBorder(new EmptyBorder(0, 4, 0, 0));
		JButton newGroup = L10N.button("plugin_extension.new_group");
		newGroup.setIcon(UIRES.get("16px.add"));
		newGroup.addActionListener(e -> createGroup());
		toolbar.add(newGroup);
		toolbar.add(new JEmptyBox(5, 5));
		JButton openDisabled = L10N.button("plugin_extension.open_disabled_folder");
		openDisabled.setIcon(UIRES.get("16px.open"));
		openDisabled.addActionListener(e -> {
			PluginToggleService.ensureDisabledFolder();
			DesktopUtils.openSafe(PluginToggleService.disabledFolder());
		});
		toolbar.add(openDisabled);
		toolbar.add(new JEmptyBox(5, 5));
		JButton checkUpdates = L10N.button("plugin_extension.check_updates");
		checkUpdates.setIcon(UIRES.get("16px.regencode"));
		checkUpdates.addActionListener(e -> checkForUpdates(checkUpdates));
		toolbar.add(checkUpdates);
		return toolbar;
	}

	/**
	 * Runs the update check off the EDT: it performs one HTTP request per plugin that declares an update
	 * source, which is far too slow to do while painting.
	 */
	private void checkForUpdates(JButton button) {
		if (updateCheckRunning)
			return;
		updateCheckRunning = true;
		button.setEnabled(false);
		List<Plugin> loaded = List.copyOf(loadedPlugins);
		List<PluginToggleService.DisabledPlugin> parked = List.copyOf(parkedPlugins);
		CompletableFuture.supplyAsync(() -> UpdateService.check(loaded, parked)).whenComplete((result, error) ->
				SwingUtilities.invokeLater(() -> {
					updateCheckRunning = false;
					button.setEnabled(true);
					if (error != null) {
						showUpdateReport(null, error);
						return;
					}
					updates = result.updates();
					// Rebuild so the rows pick up (or lose) the "new version" marker. rebuild() rather
					// than refresh(): a check does not change any setting, so the dialog must not be
					// marked as having unsaved changes.
					rebuild();
					showUpdateReport(result, null);
				}));
	}

	private void showUpdateReport(UpdateService.Result result, Throwable error) {
		StringBuilder message = new StringBuilder("<html>");
		if (error != null) {
			message.append(escape(error.toString()));
		} else if (result.updates().isEmpty()) {
			message.append(escape(L10N.t("plugin_extension.check_updates_none", result.checked())));
		} else {
			message.append(escape(L10N.t("plugin_extension.check_updates_found", result.updates().size())));
			for (UpdateService.UpdateInfo info : result.updates().values()) {
				message.append("<br><br><b>").append(escape(info.id())).append("</b>: ");
				message.append(escape(info.currentVersion() == null ? "?" : info.currentVersion()));
				message.append(" &rarr; <b>").append(escape(info.availableVersion())).append("</b>");
				for (String change : info.changes())
					message.append("<br>&nbsp;&nbsp;&bull; ").append(escape(change));
			}
		}
		if (result != null && (result.failed() > 0 || result.unsupported() > 0)) {
			message.append("<br><br><small>");
			if (result.unsupported() > 0)
				message.append(escape(L10N.t("plugin_extension.check_updates_unsupported", result.unsupported())));
			if (result.unsupported() > 0 && result.failed() > 0)
				message.append("<br>");
			if (result.failed() > 0)
				message.append(escape(L10N.t("plugin_extension.check_updates_failed", result.failed())));
			message.append("</small>");
		}
		JOptionPane.showMessageDialog(dialog, message.toString(), L10N.t("plugin_extension.check_updates_title"),
				JOptionPane.INFORMATION_MESSAGE);
	}

	private void buildGroupColumn() {
		groupColumn.removeAll();
		groupBoxes.clear();
		groupLabels.clear();
		groupRows.clear();
		for (GroupEntry entry : entries) groupColumn.add(createGroupRow(entry));
		groupColumn.add(Box.createVerticalGlue());
		groupColumn.revalidate();
		groupColumn.repaint();
	}

	private JComponent createGroupRow(GroupEntry entry) {
		JCheckBox box = new JCheckBox();
		box.setOpaque(false);
		box.setEnabled(!entry.locked());
		if (entry.locked()) {
			box.setToolTipText(L10N.t("plugin_extension.builtin_hint"));
		} else {
			box.addActionListener(e -> toggleGroup(entry));
			box.setToolTipText(L10N.t("plugin_extension.group_toggle_hint"));
		}
		JLabel label = new JLabel();
		label.setForeground(entry.locked() ? Theme.current().getAltForegroundColor() : Theme.current().getForegroundColor());
		ComponentUtils.deriveFont(label, 12);
		MouseAdapter select = new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				select(entry);
			}
		};
		box.addMouseListener(select);
		label.addMouseListener(select);
		JPanel line = stretchable();
		line.setLayout(new BorderLayout(6, 0));
		line.setOpaque(true);
		line.setBorder(new EmptyBorder(4, 4, 4, 4));
		line.add("West", box);
		line.add("Center", label);
		line.addMouseListener(select);
		groupBoxes.put(entry.key(), box);
		groupLabels.put(entry.key(), label);
		groupRows.put(entry.key(), line);
		return line;
	}

	private void select(GroupEntry entry) {
		if (entry.key().equals(selectedKey)) return;
		selectedKey = entry.key();
		paintSelection();
		buildDetail();
		syncAll();
		detail.revalidate();
		detail.repaint();
	}

	private void paintSelection() {
		for (GroupEntry entry : entries) {
			JPanel line = groupRows.get(entry.key());
			if (line == null) continue;
			boolean selected = entry.key().equals(selectedKey);
			line.setBackground(selected ? Theme.current().getAltBackgroundColor() : Theme.current().getBackgroundColor());
			line.setBorder(selected ? BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.current().getInterfaceAccentColor()),
					new EmptyBorder(4, 1, 4, 4)) :
					new EmptyBorder(4, 4, 4, 4));
		}
	}

	private void buildDetail() {
		detail.removeAll();
		pluginBoxes.clear();
		detailCounters.clear();
		GroupEntry entry = selectedEntry();
		if (entry == null) {
			detail.revalidate();
			detail.repaint();
			return;
		}
		List<Row> rows = rowsFor(entry);
		JLabel title = new JLabel(entry.name());
		title.setForeground(Theme.current().getForegroundColor());
		ComponentUtils.deriveFont(title, 13);
		JLabel counter = new JLabel();
		counter.setForeground(Theme.current().getAltForegroundColor());
		ComponentUtils.deriveFont(counter, 11);
		JComboBox<String> filterBox = new JComboBox<>(new String[] { L10N.t("plugin_extension.filter_all"),
				L10N.t("plugin_extension.filter_enabled"), L10N.t("plugin_extension.filter_disabled") });
		filterBox.setSelectedIndex(filter.ordinal());
		filterBox.setToolTipText(L10N.t("plugin_extension.filter_hint"));
		ComponentUtils.deriveFont(filterBox, 11);
		filterBox.addActionListener(_ -> {
			Filter chosen = Filter.values()[filterBox.getSelectedIndex()];
			if (chosen == filter) return;
			filter = chosen;
			showRows(rows);
		});
		JPanel lead = new JPanel();
		lead.setOpaque(false);
		lead.setLayout(new BoxLayout(lead, BoxLayout.X_AXIS));
		lead.add(title);
		lead.add(new JEmptyBox(10, 10));
		lead.add(counter);
		lead.add(new JEmptyBox(12, 12));
		lead.add(filterBox);
		JPanel header = stretchable();
		header.setLayout(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(2, 4, 6, 4));
		header.add("West", lead);
		if (entry.group() != null) {
			JButton menu = iconButton("16px.edit", L10N.t("plugin_extension.group_options"));
			menu.addActionListener(_ -> groupMenu(entry.group()).show(menu, 0, menu.getHeight()));
			lead.add(new JEmptyBox(4, 4));
			lead.add(menu);
		}
		detailCounters.put(selectedKey, counter);
		detail.add("North", header);
		detail.add("Center", detailList);
		showRows(rows);
		detail.revalidate();
		detail.repaint();
	}

	private boolean matchesFilter(Row row) {
		return switch (filter) {
			case ALL -> true;
			case ENABLED -> config.isEnabled(row.id());
			case DISABLED -> !config.isEnabled(row.id());
		};
	}

	private void showRows(List<Row> rows) {
		detailList.removeAll();
		pluginBoxes.clear();
		List<Row> visible = rows.stream().filter(this::matchesFilter).toList();
		for (Row row : visible) detailList.add(createRow(row));
		if (visible.isEmpty()) detailList.add(createEmptyHint(rows.isEmpty() ? "plugin_extension.empty_group" : "plugin_extension.filter_empty"));
		detailList.add(Box.createVerticalGlue());
		detailList.revalidate();
		detailList.repaint();
	}

	private JComponent createEmptyHint(String messageKey) {
		JLabel hint = new JLabel(L10N.t(messageKey));
		hint.setForeground(Theme.current().getAltForegroundColor());
		hint.setBorder(new EmptyBorder(4, 26, 2, 6));
		ComponentUtils.deriveFont(hint, 11);
		JPanel line = stretchable();
		line.setLayout(new BorderLayout());
		line.setOpaque(false);
		line.add("West", hint);
		return line;
	}

	private JComponent createRow(Row row) {
		JCheckBox box = new JCheckBox();
		box.setOpaque(false);
		box.setSelected(config.isEnabled(row.id()));
		box.setEnabled(row.manageable());
		box.setToolTipText(row.manageable() ? L10N.t("plugin_extension.enable_hint") :
				L10N.t(PluginToggleService.isSelf(row.id()) ?
						"plugin_extension.self_locked" :
						"plugin_extension.locked"));
		box.addActionListener(e -> {
			config.setEnabled(row.id(), box.isSelected());
			onChange();
		});
		pluginBoxes.put(row.id(), box);
		JLabel label = new JLabel(formatLabel(row));
		label.setForeground(row.manageable() ? Theme.current().getForegroundColor() :
				Theme.current().getAltForegroundColor());
		if (needsManualPark(row))
			label.setToolTipText(L10N.t("plugin_extension.pending_manual_hint"));
		else if (row.description() != null)
			label.setToolTipText(row.description());
		JButton menu = iconButton("16px.swap", L10N.t("plugin_extension.move_to"));
		menu.addActionListener(e -> assignMenu(row).show(menu, 0, menu.getHeight()));
		JPanel lead = new JPanel();
		lead.setOpaque(false);
		lead.setLayout(new BoxLayout(lead, BoxLayout.X_AXIS));
		lead.add(box);
		lead.add(new JEmptyBox(4, 4));
		lead.add(menu);
		JPanel line = stretchable();
		line.setLayout(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.setBorder(new EmptyBorder(3, 6, 3, 6));
		line.add("West", lead);
		line.add("Center", label);
		line.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				maybeShow(e);
			}
			@Override public void mouseReleased(MouseEvent e) {
				maybeShow(e);
			}
			private void maybeShow(MouseEvent e) {
				if (e.isPopupTrigger())
					assignMenu(row).show(e.getComponent(), e.getX(), e.getY());
			}
		});
		return line;
	}

	private boolean needsManualPark(Row row) {
		return row.manageable() && !row.parked() && !config.isEnabled(row.id());
	}

	private String formatLabel(Row row) {
		StringBuilder builder = new StringBuilder("<html>");
		builder.append(escape(row.name() == null ? row.id() : row.name()));
		builder.append("<br><small>");
		builder.append(L10N.t("plugin_extension.label_id")).append(": ").append(escape(row.id()));
		if (row.version() != null && !row.version().isBlank())
			builder.append(", ").append(L10N.t("plugin_extension.label_version")).append(": ")
					.append(escape(row.version()));
		if (row.author() != null && !row.author().isBlank())
			builder.append(", ").append(L10N.t("plugin_extension.label_author")).append(": ")
					.append(escape(row.author()));
		if (row.parked()) {
			builder.append(", <font color=#f24122>").append(L10N.t("plugin_extension.state_disabled")).append("</font>");
		} else {
			builder.append(", ").append(L10N.t("plugin_extension.label_loaded")).append(": ");
			builder.append(row.loaded() ?
					"<font color=#a7ed1a>" + L10N.t("plugin_extension.label_yes") + "</font>" :
					"<font color=#f24122>" + L10N.t("plugin_extension.label_no") + "</font>");
			if (needsManualPark(row))
				builder.append(" <font color=#e8a33d>[").append(L10N.t("plugin_extension.pending_manual"))
						.append("]</font>");
		}
		UpdateService.UpdateInfo update = updates.get(row.id());
		if (update != null)
			builder.append(" <font color=#4ea1f7>[").append(L10N.t("plugin_extension.update_available"))
					.append(": ").append(escape(update.availableVersion())).append("]</font>");
		builder.append("</small>");
		return builder.toString();
	}

	private static String escape(String value) {
		if (value == null)
			return "";
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JButton iconButton(String icon, String tooltip) {
		JButton button = new JButton(UIRES.get(icon));
		button.setToolTipText(tooltip);
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setFocusPainted(false);
		button.setOpaque(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	private static JPanel stretchable() {
		JPanel panel = new JPanel() {
			@Override public Dimension getMaximumSize() {
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setAlignmentX(LEFT_ALIGNMENT);
		return panel;
	}

	private static JScrollPane scrollOf(JComponent view) {
		JScrollPane scroll = new JScrollPane(view);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(12);
		return scroll;
	}

	private void toggleGroup(GroupEntry entry) {
		List<Row> rows = rowsFor(entry);
		boolean allEnabled = true;
		for (Row row : rows)
			allEnabled &= config.isEnabled(row.id());
		boolean target = !allEnabled;
		for (Row row : rows)
			if (row.manageable())
				config.setEnabled(row.id(), target);
		onChange();
	}

	private void syncAll() {
		for (GroupEntry entry : entries) {
			List<Row> rows = rowsFor(entry);
			boolean all = true;
			boolean none = true;
			int enabled = 0;
			for (Row row : rows) {
				boolean state = !row.manageable() || config.isEnabled(row.id());
				all &= state;
				none &= !state;
				if (state)
					enabled++;
			}
			JLabel label = groupLabels.get(entry.key());
			if (label != null)
				label.setText("<html>" + escape(entry.name()) + "<br><small>"
						+ L10N.t("plugin_extension.counter", enabled, rows.size()) + "</small>");
			JCheckBox box = groupBoxes.get(entry.key());
			if (box != null)
				box.setSelected(entry.locked() || all);
			JLabel counter = detailCounters.get(entry.key());
			if (counter != null) {
				counter.setText(L10N.t("plugin_extension.counter", enabled, rows.size()));
				counter.setForeground(!entry.locked() && !all && !none ?
						Theme.current().getInterfaceAccentColor() :
						Theme.current().getAltForegroundColor());
			}
		}
		for (Map.Entry<String, JCheckBox> entry : pluginBoxes.entrySet()) {
			JCheckBox box = entry.getValue();
			if (box.isEnabled())
				box.setSelected(config.isEnabled(entry.getKey()));
		}
		paintSelection();
	}

	private void onChange() {
		syncAll();
		dialog.markChanged();
		GroupEntry entry = filter == Filter.ALL ? null : selectedEntry();
		if (entry != null)
			SwingUtilities.invokeLater(() -> showRows(rowsFor(entry)));
	}

	private void createGroup() {
		String name = askName(L10N.t("plugin_extension.new_group"), L10N.t("plugin_extension.new_group_title"), "");
		if (name == null)
			return;
		PluginGroupsConfig.PluginGroup group = config.createGroup(name);
		selectedKey = group.id;
		refresh();
	}

	private void renameGroup(PluginGroupsConfig.PluginGroup group) {
		String name = askName(L10N.t("plugin_extension.rename"), L10N.t("plugin_extension.rename_title"), group.name);
		if (name == null)
			return;
		group.name = name;
		refresh();
	}

	private void deleteGroup(PluginGroupsConfig.PluginGroup group) {
		int option = JOptionPane.showConfirmDialog(dialog, L10N.t("plugin_extension.delete_confirm", group.name),
				L10N.t("plugin_extension.delete"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (option != JOptionPane.YES_OPTION)
			return;
		config.groups.remove(group);
		refresh();
	}

	private String askName(String message, String title, String initial) {
		Object result = JOptionPane.showInputDialog(dialog, message, title, JOptionPane.QUESTION_MESSAGE, null, null,
				initial);
		if (result == null)
			return null;
		String name = result.toString().strip();
		return name.isEmpty() ? null : name;
	}

	private JPopupMenu groupMenu(PluginGroupsConfig.PluginGroup group) {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem rename = new JMenuItem(L10N.t("plugin_extension.rename"));
		rename.addActionListener(e -> renameGroup(group));
		menu.add(rename);
		menu.addSeparator();
		JMenuItem delete = new JMenuItem(L10N.t("plugin_extension.delete"));
		delete.addActionListener(e -> deleteGroup(group));
		menu.add(delete);
		return menu;
	}

	private JPopupMenu assignMenu(Row row) {
		JPopupMenu menu = new JPopupMenu();
		UpdateService.UpdateInfo update = updates.get(row.id());
		if (update != null) {
			JMenuItem open = new JMenuItem(L10N.t("plugin_extension.open_update_page"));
			open.addActionListener(e -> openUpdatePage(update));
			menu.add(open);
			menu.addSeparator();
		}
		PluginGroupsConfig.PluginGroup current = config.groupOf(row.id());
		JMenu moveTo = L10N.menu("plugin_extension.move_to");
		for (PluginGroupsConfig.PluginGroup group : config.groups) {
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(group.name, group == current);
			item.addActionListener(e -> {
				config.assign(row.id(), group.id);
				refresh();
			});
			moveTo.add(item);
		}
		if (!config.groups.isEmpty())
			moveTo.addSeparator();
		JMenuItem newGroup = new JMenuItem(L10N.t("plugin_extension.new_group"));
		newGroup.addActionListener(e -> {
			String name = askName(L10N.t("plugin_extension.new_group"), L10N.t("plugin_extension.new_group_title"), "");
			if (name == null)
				return;
			config.assign(row.id(), config.createGroup(name).id);
			refresh();
		});
		moveTo.add(newGroup);
		menu.add(moveTo);
		if (current != null) {
			JMenuItem remove = new JMenuItem(L10N.t("plugin_extension.remove_from_group"));
			remove.addActionListener(e -> {
				config.unassign(row.id());
				refresh();
			});
			menu.add(remove);
		}
		return menu;
	}

	private void openUpdatePage(UpdateService.UpdateInfo update) {
		// The update JSON only advertises the version, it carries no download URL, and mcreator.net
		// answers non-browser clients with a challenge page. So the plugin page is the only reliable
		// place to download from - open it instead of pretending to install anything.
		if (update.pageID() > 0) {
			DesktopUtils.browseSafe("https://mcreator.net/node/" + update.pageID());
			return;
		}
		JOptionPane.showMessageDialog(dialog, L10N.t("plugin_extension.update_no_page", update.id()),
				L10N.t("plugin_extension.check_updates_title"), JOptionPane.INFORMATION_MESSAGE);
	}

	private void refresh() {
		rebuild();
		dialog.markChanged();
	}

	void applyChanges() {
		PluginToggleService.ApplyResult result = PluginToggleService.apply(PluginLoader.INSTANCE.getPlugins(),
				config.disabledSet());
		// Re-read the plugin folders so the rows (and their enable checkboxes) reflect what apply() just
		// did. Without this the panel keeps showing pre-move state until the dialog is reopened.
		reload();
		// Saved after reload() so the pruned group entries and the self-exclusion land on disk too.
		config.save();
		if (result.deferred() > 0)
			PluginToggleService.parkOnExit();
		if (!result.needsRestart())
			return;
		showRestartPrompt(result);
	}

	private void showRestartPrompt(PluginToggleService.ApplyResult result) {
		String message;
		if (!result.moved() && result.deferred() == 0) {
			// Nothing was moved at all: do not claim that plugins were enabled, they were not.
			message = L10N.t("plugin_extension.restart_message_stuck", result.blocked());
		} else {
			message = L10N.t(result.deferred() > 0 ?
					"plugin_extension.restart_message_deferred" :
					"plugin_extension.restart_message");
			if (result.blocked() > 0)
				message = message + "<br><br>" + L10N.t("plugin_extension.restart_message_blocked", result.blocked());
		}
		if (!RestartService.canRestart()) {
			JOptionPane.showMessageDialog(dialog, message, L10N.t("plugin_extension.restart_title"),
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		Object[] options = { L10N.t("plugin_extension.restart_now"), L10N.t("plugin_extension.restart_later") };
		int choice = JOptionPane.showOptionDialog(dialog, message, L10N.t("plugin_extension.restart_title"),
				JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
		if (choice != 0)
			return;
		RestartService.arm();
		try {
			RestartService.closeApplication();
		} finally {
			RestartService.disarm();
		}
	}

	private static void hookSaveButtons(PreferencesDialog dialog, PluginGroupsPanel panel) {
		Set<String> labels = new LinkedHashSet<>();
		labels.add(L10N.button("action.common.apply").getText());
		labels.add(L10N.button("dialog.preferences.save").getText());
		ActionListener apply = e -> panel.applyChanges();
		for (JButton button : descendantsOfType(dialog, JButton.class)) {
			if (!labels.contains(button.getText()))
				continue;
			// install() runs again whenever the preferences dialog is shown; the dialog may reuse its
			// buttons, so drop a listener installed by an earlier panel before adding the current one.
			// Otherwise one click would run applyChanges() twice (double file moves, two restart prompts).
			Object previous = button.getClientProperty(HOOKED_PROPERTY);
			if (previous instanceof ActionListener stale)
				button.removeActionListener(stale);
			button.putClientProperty(HOOKED_PROPERTY, apply);
			button.addActionListener(apply);
		}
	}

	private static JList<?> findPluginList(Container root) {
		List<JList<?>> lists = new ArrayList<>();
		collectLists(root, lists);
		for (JList<?> list : lists) {
			ListCellRenderer<?> renderer = list.getCellRenderer();
			if (renderer != null && renderer.getClass().getName().contains("PluginsListCellRenderer"))
				return list;
		}
		for (JList<?> list : lists) {
			ListModel<?> model = list.getModel();
			if (model.getSize() > 0 && model.getElementAt(0) instanceof Plugin)
				return list;
		}
		return null;
	}

	private static void collectLists(Container root, List<JList<?>> lists) {
		for (Component component : root.getComponents()) {
			if (component instanceof JList<?> list) {
				lists.add(list);
			} else if (component instanceof Container container) {
				collectLists(container, lists);
			}
		}
	}

	private static <T> List<T> descendantsOfType(Container root, Class<T> type) {
		List<T> result = new ArrayList<>();
		for (Component component : root.getComponents()) {
			if (type.isInstance(component))
				result.add(type.cast(component));
			if (component instanceof Container container)
				result.addAll(descendantsOfType(container, type));
		}
		return result;
	}

	@Override public Dimension getPreferredScrollableViewportSize() {
		return getPreferredSize();
	}

	@Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
		return 12;
	}

	@Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
		return Math.max(visibleRect.height - 24, 24);
	}

	@Override public boolean getScrollableTracksViewportWidth() {
		return true;
	}

	@Override public boolean getScrollableTracksViewportHeight() {
		return true;
	}
}
