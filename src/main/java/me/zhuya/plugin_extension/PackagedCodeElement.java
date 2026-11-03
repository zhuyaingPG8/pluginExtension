package me.zhuya.plugin_extension;

import net.mcreator.element.GeneratableElement;
import net.mcreator.workspace.elements.ModElement;

@SuppressWarnings("unused") public class PackagedCodeElement extends GeneratableElement {
	public String targetPackage = "";
	public String classKind = "class";
	public String superClass = "";
	public String interfaces = "";
	public String recordComponents = "";

	public String targetPackagePath() {
		return targetPackage == null ? "" : targetPackage.replace('.', '/');
	}

	private PackagedCodeElement() {
		this(null);
	}

	public PackagedCodeElement(ModElement element) {
		super(element);
	}
}
