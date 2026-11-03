package me.zhuya.plugin_extension;

import net.mcreator.element.ModElementType;
import net.mcreator.element.ModElementTypeLoader;
import net.mcreator.generator.GeneratorFlavor;

import java.util.concurrent.atomic.AtomicBoolean;

final class PackagedCodeType {
	static final String REGISTRY_NAME = "packagedcode";
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	static void register() {
		if (!REGISTERED.compareAndSet(false, true)) return;
		ModElementTypeLoader.register(new ModElementType<>(REGISTRY_NAME, "code", null, PackagedCodeGUI::new, PackagedCodeElement.class)
				.coveredOn(GeneratorFlavor.baseLanguage(GeneratorFlavor.BaseLanguage.JAVA)));
	}
}
