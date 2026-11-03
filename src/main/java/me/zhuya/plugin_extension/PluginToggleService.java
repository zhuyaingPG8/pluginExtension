package me.zhuya.plugin_extension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcreator.io.UserFolderManager;
import net.mcreator.io.zip.ZipIO;
import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.PluginLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PluginToggleService {
	private static final Logger LOG = LogManager.getLogger("pluginExtension");

	public static final String DISABLED_DIR = ".disabled";

	public record DisabledPlugin(String id, String name, String version, String author, String description, File file,
								 String updateJSONURL, int pluginPageID) {}

	private static final class PluginsFolderHolder {
		private static final File FOLDER = UserFolderManager.getFileFromUserFolder("plugins").getAbsoluteFile();
	}

	public static File pluginsFolder() {
		return PluginsFolderHolder.FOLDER;
	}

	public static File disabledFolder() {
		return new File(pluginsFolder(), DISABLED_DIR);
	}

	public static void ensureDisabledFolder() {
		disabledFolder().mkdirs();
	}

	public static boolean isManageable(Plugin plugin) {
		return plugin != null && isParkable(plugin.getID(), plugin.isBuiltin(), plugin.getFile());
	}

	private record ArchiveKey(String path, long lastModified, long length) {
		static ArchiveKey of(File file) {
			File absolute = file.getAbsoluteFile();
			return new ArchiveKey(absolute.getPath(), absolute.lastModified(), absolute.length());
		}
	}

	private static final Object ARCHIVE_CACHE_LOCK = new Object();
	private static final int ARCHIVE_CACHE_LIMIT = 256;
	private static final Set<ArchiveKey> ZIP_CACHE = new HashSet<>();
	private static final Map<ArchiveKey, DisabledPlugin> META_CACHE = new HashMap<>();

	static boolean isParkable(String pluginID, boolean builtin, File file) {
		if (builtin || isSelf(pluginID))
			return false;
		if (file == null || !file.isFile() || !isZipArchive(file))
			return false;
		File parent = file.getAbsoluteFile().getParentFile();
		return parent != null && parent.equals(pluginsFolder());
	}

	private static boolean isZipArchive(File file) {
		ArchiveKey key = ArchiveKey.of(file);
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (ZIP_CACHE.contains(key)) return true;
		}
		boolean result = ZipIO.checkIfZip(file);
		if (!result) return false;
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (ZIP_CACHE.size() >= ARCHIVE_CACHE_LIMIT) {
				ZIP_CACHE.clear();
				META_CACHE.clear();
			}
			ZIP_CACHE.add(key);
		}
		return true;
	}

	private static volatile String selfPluginID;
	public static void setSelfPluginID(String pluginID) {
		selfPluginID = pluginID;
	}

	public static boolean isSelf(String pluginID) {
		return pluginID != null && pluginID.equals(selfPluginID);
	}

	public record ApplyResult(boolean moved, int deferred, int blocked) {
		public boolean needsRestart() {
			return moved || deferred > 0 || blocked > 0;
		}
	}

	public static ApplyResult apply(Collection<Plugin> loadedPlugins, Set<String> disabledIDs) {
		File plugins = pluginsFolder();
		File disabled = disabledFolder();
		boolean moved = false;
		int deferred = 0;
		int blocked = 0;
		for (Plugin plugin : loadedPlugins) {
			if (!isManageable(plugin) || !disabledIDs.contains(plugin.getID())) continue;
			File file = plugin.getFile();
			if (move(file, new File(disabled, file.getName()))) moved = true;
			else deferred++;
		}
		for (File file : listArchives(disabled)) {
			DisabledPlugin parked = describe(file);
			if (parked == null || disabledIDs.contains(parked.id())) continue;
			File target = new File(plugins, file.getName());
			if (move(file, target)) moved = true;
			else blocked++;
		}
		return new ApplyResult(moved, deferred, blocked);
	}

	private static final AtomicBoolean PARK_ON_EXIT_ARMED = new AtomicBoolean();
	public static void parkOnExit() {
		if (!PARK_ON_EXIT_ARMED.compareAndSet(false, true)) return;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				Set<String> disabledIDs = PluginGroupsConfig.get().disabledSet();
				if (!needsParking(disabledIDs)) return;
				releasePluginArchives();
				apply(PluginLoader.INSTANCE.getPlugins(), disabledIDs);
			} catch (Exception _) {}
		}, "plugin-groups-park"));
	}

	private static boolean needsParking(Set<String> disabledIDs) {
		for (Plugin plugin : PluginLoader.INSTANCE.getPlugins())
			if (disabledIDs.contains(plugin.getID()) && isManageable(plugin)) return true;
		return false;
	}

	private static void releasePluginArchives() {
		ClassLoader ownLoader = PluginToggleService.class.getClassLoader();
		for (JavaPlugin javaPlugin : loadedJavaPlugins()) {
			ClassLoader loader = javaPlugin.getClass().getClassLoader();
			if (loader == ownLoader || loader == null || isAncestorOf(loader, ownLoader)) continue;
			if (loader instanceof URLClassLoader urlClassLoader) {
				try {
					urlClassLoader.close();
				} catch (IOException _) {}
			}
		}
		PluginLoader pluginLoader = PluginLoader.INSTANCE;
		if (pluginLoader != ownLoader && !isAncestorOf(pluginLoader, ownLoader)) {
			try {
				pluginLoader.close();
			} catch (IOException _) {}
		}
	}

	private static boolean isAncestorOf(ClassLoader ancestor, ClassLoader loader) {
		for (ClassLoader current = loader; current != null; current = current.getParent())
			if (current == ancestor) return true;
		return false;
	}

	@SuppressWarnings("unchecked") private static Collection<JavaPlugin> loadedJavaPlugins() {
		try {
			Method method = PluginLoader.class.getDeclaredMethod("getJavaPlugins");
			method.setAccessible(true);
			return (Collection<JavaPlugin>) method.invoke(PluginLoader.INSTANCE);
		} catch (Exception e) {
			return List.of();
		}
	}

	public static List<DisabledPlugin> listDisabled() {
		List<DisabledPlugin> result = new ArrayList<>();
		for (File file : listArchives(disabledFolder())) {
			DisabledPlugin plugin = describe(file);
			if (plugin != null) result.add(plugin);
		}
		return result;
	}

	private static DisabledPlugin describe(File archive) {
		ArchiveKey key = ArchiveKey.of(archive);
		synchronized (ARCHIVE_CACHE_LOCK) {
			DisabledPlugin cached = META_CACHE.get(key);
			if (cached != null) return cached;
		}
		DisabledPlugin parsed = parseDescribe(archive);
		if (parsed == null) return null;
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (META_CACHE.size() >= ARCHIVE_CACHE_LIMIT) {
				ZIP_CACHE.clear();
				META_CACHE.clear();
			}
			META_CACHE.put(key, parsed);
		}
		return parsed;
	}

	private static DisabledPlugin parseDescribe(File archive) {
		try {
			String raw = ZipIO.readCodeInZip(archive, "plugin.json");
			if (raw == null) return null;
			JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
			String id = text(root, "id", "");
			JsonObject info = root.has("info") && root.get("info").isJsonObject() ?
					root.getAsJsonObject("info") :
					new JsonObject();
			return new DisabledPlugin(id, text(info, "name", id), text(info, "version", ""),
					text(info, "author", ""), text(info, "description", ""), archive,
					info.has("updateJSONURL") && !info.get("updateJSONURL").isJsonNull() ?
							info.get("updateJSONURL").getAsString() :
							null,
					info.has("pluginPageID") && !info.get("pluginPageID").isJsonNull() ?
							info.get("pluginPageID").getAsInt() :
							0);
		} catch (Exception e) {
			return null;
		}
	}

	private static String text(JsonObject object, String key, String fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
	}

	private static List<File> listArchives(File folder) {
		List<File> archives = new ArrayList<>();
		File[] files = folder.listFiles();
		for (File file : files != null ? files : new File[0])
			if (file.isFile() && isZipArchive(file)) archives.add(file);
		return archives;
	}

	private static boolean move(File from, File to) {
		try {
			File parent = to.getParentFile();
			if (parent != null) parent.mkdirs();
			if (isSameFile(from, to)) return true;
			if (to.exists()) {
				LOG.warn("Not moving {} to {}: the target already exists", from, to);
				return false;
			}
			Files.move(from.toPath(), to.toPath());
			return true;
		} catch (IOException e) {
			LOG.warn("Failed to move {} to {}: {}", from, to, e.toString());
			return false;
		}
	}

	private static boolean isSameFile(File from, File to) {
		File source = from.getAbsoluteFile();
		File target = to.getAbsoluteFile();
		if (source.equals(target)) return true;
		try {
			return source.getCanonicalFile().equals(target.getCanonicalFile());
		} catch (IOException e) {
			return false;
		}
	}
}
