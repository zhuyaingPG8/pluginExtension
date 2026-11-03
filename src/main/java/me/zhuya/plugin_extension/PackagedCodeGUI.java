package me.zhuya.plugin_extension;

import net.mcreator.ui.MCreator;
import net.mcreator.ui.component.util.ComponentUtils;
import net.mcreator.ui.component.util.PanelUtils;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.modgui.ModElementGUI;
import net.mcreator.ui.validation.ValidationGroup;
import net.mcreator.ui.validation.component.VTextField;
import net.mcreator.workspace.elements.ModElement;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class PackagedCodeGUI extends ModElementGUI<PackagedCodeElement> {
	static final String[] CLASS_KINDS = { "class", "final class", "abstract class", "interface", "@interface", "enum", "record" };
	private final JComboBox<String> targetPackage = new JComboBox<>();
	private final JLabel resolved = new JLabel();
	private final JComboBox<String> classKind = new JComboBox<>(CLASS_KINDS);
	private final VTextField superClass = new VTextField(24);
	private final VTextField interfaces = new VTextField(24);
	private final VTextField recordComponents = new VTextField(24);
	private final JLabel superClassLabel = L10N.label("elementgui.packagedcode.super_class");
	private final JLabel interfacesLabel = L10N.label("elementgui.packagedcode.interfaces");
	private final JLabel recordComponentsLabel = L10N.label("elementgui.packagedcode.record_components");
	private final ValidationGroup pageGroup = new ValidationGroup();
	private static final int FIELD_WIDTH = 320;

	public PackagedCodeGUI(MCreator mcreator, ModElement modElement, boolean editingMode) {
		super(mcreator, modElement, editingMode);
		initGUI();
		super.finalizeGUI();
	}

	@Override protected void initGUI() {
		targetPackage.setEditable(true);
		ComponentUtils.deriveFont(targetPackage, 15);
		collectPackages(mcreator.getGenerator().getGeneratorPackageRoot(), "").forEach(targetPackage::addItem);
		targetPackage.addActionListener(_ -> updateResolved());
		targetPackage.getEditor().getEditorComponent().addKeyListener(new KeyAdapter() {
			@Override public void keyReleased(KeyEvent e) {
				updateResolved();
			}
		});
		resolved.setForeground(Theme.current().getAltForegroundColor());
		ComponentUtils.deriveFont(resolved, 13);
		ComponentUtils.deriveFont(classKind, 15);
		classKind.addActionListener(_ -> updateKindFields());
		ComponentUtils.deriveFont(superClass, 15);
		ComponentUtils.deriveFont(interfaces, 15);
		ComponentUtils.deriveFont(recordComponents, 15);

		int fieldHeight = tallest(targetPackage, classKind, superClass, interfaces, recordComponents);

		JLabel targetPackageLabel = L10N.label("elementgui.packagedcode.target_package");
		JLabel resolvedLabel = L10N.label("elementgui.packagedcode.resolved_label");
		JLabel classKindLabel = L10N.label("elementgui.packagedcode.class_kind");

		JPanel rows = new JPanel(new GridBagLayout());
		rows.setOpaque(false);
		addRow(rows, 0, targetPackageLabel, holder(targetPackage, fieldHeight));
		addRow(rows, 1, resolvedLabel, holder(resolved, fieldHeight));
		addRow(rows, 2, classKindLabel, holder(classKind, fieldHeight));
		addRow(rows, 3, superClassLabel, holder(superClass, fieldHeight));
		addRow(rows, 4, interfacesLabel, holder(interfaces, fieldHeight));
		addRow(rows, 5, recordComponentsLabel, holder(recordComponents, fieldHeight));

		JPanel section = new JPanel(new BorderLayout(25, 0));
		section.setOpaque(false);
		section.add("Center", PanelUtils.centerInPanel(rows));
		section.setMaximumSize(new Dimension(620, 200));
		ComponentUtils.makeSection(section, L10N.t(modElement.isCodeLocked() ? "elementgui.packagedcode.locked_hint" : "elementgui.packagedcode.target_package_hint"));

		JPanel page = new JPanel(new BorderLayout());
		page.setOpaque(false);
		page.add(PanelUtils.totalCenterInPanel(section));

		updateKindFields();
		updateResolved();
		addPage(L10N.t("elementgui.packagedcode.page"), page).validate(pageGroup);
	}

	private void updateKindFields() {
		String kind = Objects.toString(classKind.getSelectedItem(), "");
		boolean record;
		boolean canExtend;
		boolean canImplement;
		switch (kind) {
			case "class", "final class", "abstract class" -> {
				record = false;
				canExtend = true;
				canImplement = true;
			}
			case "interface" -> {
				record = false;
				canExtend = true;
				canImplement = false;
			}
			case "record" -> {
				record = true;
				canExtend = false;
				canImplement = true;
			}
			case "enum" -> {
				record = false;
				canExtend = false;
				canImplement = true;
			}
			default -> {
				record = false;
				canExtend = false;
				canImplement = false;
			}
		}
		superClass.setEnabled(canExtend);
		superClassLabel.setEnabled(canExtend);
		superClassLabel.setText(L10N.t(kind.equals("interface") ? "elementgui.packagedcode.super_interfaces" : "elementgui.packagedcode.super_class"));
		interfaces.setEnabled(canImplement);
		interfacesLabel.setEnabled(canImplement);
		recordComponents.setEnabled(record);
		recordComponentsLabel.setEnabled(record);
	}

	private Set<String> collectPackages(File folder, String prefix) {
		Set<String> packages = new TreeSet<>();
		if (folder == null) return packages;
		File[] children = folder.listFiles();
		if (children == null) return packages;
		boolean holdsCode = false;
		for (File child : children){
			if (child.isFile() && child.getName().endsWith(".java")) holdsCode = true;
		}
		if (holdsCode && !prefix.isEmpty()){
			packages.add(prefix);
		}
		for (File child : children){
			if (child.isDirectory()){
				packages.addAll(collectPackages(child, prefix.isEmpty() ? child.getName() : prefix + "." + child.getName()));
			}
		}
		return packages;
	}

	private void updateResolved() {
		String value = normalize(Objects.toString(targetPackage.getEditor().getItem(), ""));
		resolved.setText(value.isEmpty() ? mcreator.getWorkspaceSettings().getModElementsPackage() : mcreator.getWorkspaceSettings().getModElementsPackage() + "." + value);
	}

	private static int tallest(JComponent... fields) {
		int height = 0;
		for (JComponent field : fields){
			height = Math.max(height, field.getPreferredSize().height);
		}
		return height;
	}

	private static void addRow(JPanel rows, int row, JComponent label, JComponent field) {
		GridBagConstraints left = new GridBagConstraints();
		left.gridx = 0;
		left.gridy = row;
		left.anchor = GridBagConstraints.EAST;
		left.insets = new Insets(2, 0, 2, 10);
		rows.add(label, left);

		GridBagConstraints right = new GridBagConstraints();
		right.gridx = 1;
		right.gridy = row;
		right.anchor = GridBagConstraints.WEST;
		right.insets = new Insets(2, 0, 2, 0);
		rows.add(field, right);
	}

	private static JComponent holder(JComponent field, int height) {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);
		panel.setPreferredSize(new Dimension(FIELD_WIDTH, height));
		panel.add("Center", field);
		return panel;
	}

	@Override protected void openInEditingMode(PackagedCodeElement element) {
		targetPackage.setSelectedItem(Objects.toString(element.targetPackage, ""));
		classKind.setSelectedItem(element.classKind == null ? CLASS_KINDS[0] : element.classKind);
		superClass.setText(element.superClass == null ? "" : element.superClass);
		interfaces.setText(element.interfaces == null ? "" : element.interfaces);
		recordComponents.setText(element.recordComponents == null ? "" : element.recordComponents);
		updateKindFields();
		updateResolved();
	}

	@Override public PackagedCodeElement getElementFromGUI() {
		PackagedCodeElement element = new PackagedCodeElement(modElement);
		element.targetPackage = normalize(Objects.toString(targetPackage.getEditor().getItem(), ""));
		element.classKind = Objects.toString(classKind.getSelectedItem(), "");
		element.superClass = normalizeTypeList(superClass.getText());
		element.interfaces = normalizeTypeList(interfaces.getText());
		element.recordComponents = recordComponents.getText().trim();
		return element;
	}

	@Override protected void afterGeneratableElementGenerated() {
		if (!isEditingMode()) modElement.setCodeLock(true);
	}

	static String normalize(String value) {
		return PackageNames.normalize(value);
	}

	static String normalizeTypeList(String value) {
		return PackageNames.normalizeTypeList(value);
	}

	@Override public URI contextURL() {
		return null;
	}
}
