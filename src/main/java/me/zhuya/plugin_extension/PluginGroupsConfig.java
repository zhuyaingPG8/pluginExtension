package me.zhuya.plugin_extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.mcreator.io.FileIO;
import net.mcreator.io.UserFolderManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PluginGroupsConfig {
	private static final String FILE_NAME = "plugin_extension.json";
	private static final Set<String> RESERVED_GROUP_IDS = Set.of("__ungrouped__", "__builtin__");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static PluginGroupsConfig instance;
	public int version = 1;
	public List<PluginGroup> groups = new ArrayList<>();
	public List<String> disabled = new ArrayList<>();

	public static class PluginGroup {
		public String id = "";
		public String name = "";
		public boolean collapsed = false;
		public List<String> plugins = new ArrayList<>();
		public PluginGroup() {}
		public PluginGroup(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	public static synchronized PluginGroupsConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	public static synchronized void invalidate() {
		instance = null;
	}

	private static PluginGroupsConfig load() {
		File file = getConfigFile();
		if (file.isFile()) {
			try {
				PluginGroupsConfig config = GSON.fromJson(FileIO.readFileToString(file), PluginGroupsConfig.class);
				if (config != null) {
					config.sanitize();
					return config;
				}
			} catch (JsonSyntaxException | IllegalStateException _) {}
		}
		return new PluginGroupsConfig();
	}

	private void sanitize() {
		if (groups == null)
			groups = new ArrayList<>();
		if (disabled == null)
			disabled = new ArrayList<>();
		Set<String> seenGroupIDs = new LinkedHashSet<>();
		groups.removeIf(group -> {
			if (group == null || group.id == null || group.id.isBlank() || !seenGroupIDs.add(group.id))
				return true;
			if (RESERVED_GROUP_IDS.contains(group.id))
				return true;
			if (group.name == null || group.name.isBlank())
				group.name = group.id;
			if (group.plugins == null)
				group.plugins = new ArrayList<>();
			group.plugins.removeIf(id -> id == null || id.isBlank());
			group.plugins = new ArrayList<>(new LinkedHashSet<>(group.plugins));
			return false;
		});
		disabled.removeIf(id -> id == null || id.isBlank());
		disabled = new ArrayList<>(new LinkedHashSet<>(disabled));
	}

	public void save() {
		synchronized (PluginGroupsConfig.class) {
			try {
				FileIO.writeStringToFile(GSON.toJson(this), getConfigFile());
			} catch (Exception _) {}
		}
	}

	public static File getConfigFile() {
		return UserFolderManager.getFileFromUserFolder(FILE_NAME);
	}
	public boolean isEnabled(String pluginID) {
		return !disabled.contains(pluginID);
	}

	public void setEnabled(String pluginID, boolean enabled) {
		if (enabled)
			disabled.remove(pluginID);
		else if (!disabled.contains(pluginID))
			disabled.add(pluginID);
	}

	public Set<String> disabledSet() {
		return new LinkedHashSet<>(disabled);
	}

	public PluginGroup getGroup(String groupID) {
		for (PluginGroup group : groups)
			if (group.id.equals(groupID))
				return group;
		return null;
	}

	public PluginGroup groupOf(String pluginID) {
		for (PluginGroup group : groups)
			if (group.plugins.contains(pluginID))
				return group;
		return null;
	}

	public PluginGroup createGroup(String name) {
		PluginGroup group = new PluginGroup(UUID.randomUUID().toString(), name);
		groups.add(group);
		return group;
	}

	public PluginGroup assign(String pluginID, String groupID) {
		unassign(pluginID);
		if (groupID == null)
			return null;
		PluginGroup group = getGroup(groupID);
		if (group != null)
			group.plugins.add(pluginID);
		return group;
	}

	public void unassign(String pluginID) {
		for (PluginGroup group : groups)
			group.plugins.remove(pluginID);
	}
}
