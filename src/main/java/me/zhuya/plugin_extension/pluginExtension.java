package me.zhuya.plugin_extension;

import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.events.ApplicationLoadedEvent;
import net.mcreator.plugin.events.PreGeneratorsLoadingEvent;
import net.mcreator.plugin.events.ui.PreferencesDialogEvent;
import net.mcreator.plugin.events.workspace.MCreatorLoadedEvent;
import net.mcreator.ui.dialogs.preferences.PreferencesDialog;

import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class pluginExtension extends JavaPlugin {
	private static final Logger LOG = LogManager.getLogger("pluginExtension");

	public pluginExtension(Plugin plugin) {
		super(plugin);
		PluginToggleService.setSelfPluginID(plugin.getID());
		RestartService.setArchive(plugin.getFile());
		addListener(ApplicationLoadedEvent.class, event -> RestartService.setApplication(event.getMCreatorApplication()));
		addListener(PreGeneratorsLoadingEvent.class, _ -> PackagedCodeType.register());
		addListener(MCreatorLoadedEvent.class, event -> SwingUtilities.invokeLater(() -> WorkspaceFolderSupport.install(event.getMCreator())));
		addListener(PreferencesDialogEvent.SectionsLoaded.class, event -> {
			PreferencesDialog dialog = event.getPreferencesDialog();
			SwingUtilities.invokeLater(() -> PluginGroupsPanel.install(dialog));
		});
		LOG.info("plugin was loaded");
	}
}
