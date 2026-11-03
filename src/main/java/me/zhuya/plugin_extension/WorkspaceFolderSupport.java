package me.zhuya.plugin_extension;

import net.mcreator.ui.MCreator;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.init.UIRES;
import net.mcreator.ui.validation.ValidationResult;
import net.mcreator.ui.validation.optionpane.OptionPaneValidator;
import net.mcreator.ui.validation.optionpane.VOptionPane;
import net.mcreator.ui.variants.modmaker.ModMaker;
import net.mcreator.ui.workspace.WorkspacePanel;
import net.mcreator.workspace.elements.FolderElement;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.regex.Pattern;

public final class WorkspaceFolderSupport {
	private static final String TOOLTIP = "plugin_extension.new_folder.tooltip";
	private static final Pattern ILLEGAL_FOLDER_CHARS = Pattern.compile(".*[/\\\\:*?\"<>|].*");

	public static void install(MCreator mcreator) {
		if (!(mcreator instanceof ModMaker modMaker)) return;

		WorkspacePanel panel = modMaker.getWorkspacePanel();
		if (panel == null) return;

		JButton original = findButton(panel, L10N.t("workspace.elements.folders.add_tooltip"));
		if (original == null || !(original.getParent() instanceof Container toolbar)) {
			return;
		}

		if (findButton(toolbar, L10N.t(TOOLTIP)) != null) return;

		JButton button = new JButton(UIRES.get("laf.newFolder"));
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		button.setToolTipText(L10N.t(TOOLTIP));
		button.addActionListener(event -> createFolder(mcreator, panel, button));

		toolbar.add(button, indexAfter(toolbar, original));
		toolbar.revalidate();
		toolbar.repaint();
	}

	private static void createFolder(MCreator mcreator, WorkspacePanel panel, Component parent) {
		String name = VOptionPane.showInputDialog(mcreator, L10N.t("plugin_extension.new_folder.message"),
				L10N.t("plugin_extension.new_folder.title"), null, new OptionPaneValidator() {
					@Override public ValidationResult validate(JComponent component) {
						String candidate = ((JTextField) component).getText();

						String problem = folderNameProblem(candidate);
						if (problem != null) return new ValidationResult(ValidationResult.Type.ERROR, L10N.t(problem));

						FolderElement folder = new FolderElement(candidate.strip(), panel.currentFolder);
						for (FolderElement existing : mcreator.getWorkspace().getFoldersRoot()
								.getRecursiveFolderChildren())
							if (existing.equals(folder))
								return new ValidationResult(ValidationResult.Type.ERROR,
										L10N.t("workspace.elements.folders.add.error_exists"));
						return ValidationResult.PASSED;
					}
				});

		if (name == null) return;

		panel.currentFolder.addChild(new FolderElement(name.strip(), panel.currentFolder));
		mcreator.getWorkspace().markDirty();
		reload(panel);

		parent.requestFocusInWindow();
	}

	private static void reload(WorkspacePanel panel) {
		WorkspacePanel.WorkspacePanelMods tab = panel.getVerticalTab(WorkspacePanel.WorkspacePanelMods.class);
		if (tab != null) tab.reloadElements();
	}

	static String folderNameProblem(String name) {
		String candidate = name == null ? "" : name.strip();

		if (candidate.isEmpty()) return "plugin_extension.new_folder.error_empty";
		if (candidate.equals(".") || candidate.equals("..")) return "plugin_extension.new_folder.error_reserved";
		if (ILLEGAL_FOLDER_CHARS.matcher(candidate).matches()) return "plugin_extension.new_folder.error_illegal";
		if (candidate.endsWith(".")) return "plugin_extension.new_folder.error_trailing";

		return null;
	}

	private static JButton findButton(Container root, String tooltip) {
		for (Component component : root.getComponents()) {
			if (component instanceof JButton button && tooltip.equals(button.getToolTipText()))
				return button;
			if (component instanceof Container container) {
				JButton found = findButton(container, tooltip);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	private static int indexAfter(Container container, Component component) {
		List<Component> children = List.of(container.getComponents());
		return children.indexOf(component) + 1;
	}
}
