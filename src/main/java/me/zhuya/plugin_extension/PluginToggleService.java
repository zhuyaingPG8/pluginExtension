package me.zhuya.plugin_extension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcreator.io.UserFolderManager;
import net.mcreator.io.zip.ZipIO;
import net.mcreator.plugin.JavaPlugin;
import net.mcreator.plugin.Plugin;
import net.mcreator.plugin.PluginLoader;

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
	public static final String DISABLED_DIR = ".disabled";

	public record DisabledPlugin(String id, String name, String version, String author, String description, File file) {}

	/**
	 * UserFolderManager resolves MCREATOR_HOME from the environment on every call; the location cannot change while
	 * the JVM runs, so resolve it once. The holder keeps this out of static initialisation of this class, so no
	 * MCreator API is touched before it is actually needed.
	 */
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

	/**
	 * Identity of the archive contents on disk. Mirrors what a reader would actually see: the resolved absolute path
	 * plus modification time and length, so an archive replaced by hand (or moved by us) is never served from cache.
	 */
	private record ArchiveKey(String path, long lastModified, long length) {
		static ArchiveKey of(File file) {
			File absolute = file.getAbsoluteFile();
			return new ArchiveKey(absolute.getPath(), absolute.lastModified(), absolute.length());
		}
	}

	/**
	 * Guards both caches below. Never held while {@link ZipIO} or the gson parser runs: probing happens outside the
	 * lock and only the finished value is published, so a slow archive can not block other callers.
	 */
	private static final Object ARCHIVE_CACHE_LOCK = new Object();
	/** Archives already confirmed to be zips, per archive version; only positive answers are kept (see isZipArchive). */
	private static final Set<ArchiveKey> ZIP_CACHE = new HashSet<>();
	private static final int ARCHIVE_CACHE_LIMIT = 256;
	/** plugin.json metadata is only valid for the archive version it was read from. */
	private static final Map<ArchiveKey, String> JSON_CACHE = new HashMap<>();

	static boolean isParkable(String pluginID, boolean builtin, File file) {
		if (builtin || isSelf(pluginID))
			return false;
		if (file == null || !file.isFile() || !isZipArchive(file))
			return false;
		File parent = file.getAbsoluteFile().getParentFile();
		return parent != null && parent.equals(pluginsFolder());
	}

	/**
	 * {@link ZipIO#checkIfZip(File)} opens the file, so a negative answer is thrown away instead of cached: it also
	 * happens when a scanner or an editor briefly holds an archive open, and caching that would hide a perfectly good
	 * plugin from {@link #listDisabled()} until the file's timestamp changed. Positives are cached per archive version.
	 */
	private static boolean isZipArchive(File file) {
		ArchiveKey key = ArchiveKey.of(file);
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (ZIP_CACHE.contains(key))
				return true;
		}
		boolean result = ZipIO.checkIfZip(file);
		if (!result)
			return false;
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (ZIP_CACHE.size() >= ARCHIVE_CACHE_LIMIT) {
				// Both caches are keyed by archive version, so dropping everything can only cause a re-read.
				ZIP_CACHE.clear();
				JSON_CACHE.clear();
			}
			ZIP_CACHE.add(key);
		}
		return true;
	}

	private static String selfPluginID;
	public static void setSelfPluginID(String pluginID) {
		selfPluginID = pluginID;
	}

	public static boolean isSelf(String pluginID) {
		return pluginID != null && pluginID.equals(selfPluginID);
	}

	public record ApplyResult(boolean moved, int deferred) {
		public boolean needsRestart() {
			return moved || deferred > 0;
		}
	}

	public static ApplyResult apply(Collection<Plugin> loadedPlugins, Set<String> disabledIDs) {
		File plugins = pluginsFolder();
		File disabled = disabledFolder();
		boolean moved = false;
		int deferred = 0;
		for (Plugin plugin : loadedPlugins) {
			if (!isManageable(plugin) || !disabledIDs.contains(plugin.getID()))
				continue;
			File file = plugin.getFile();
			if (move(file, new File(disabled, file.getName())))
				moved = true;
			else
				deferred++;
		}
		for (File file : listArchives(disabled)) {
			DisabledPlugin parked = describe(file);
			if (parked == null || disabledIDs.contains(parked.id()))
				continue;
			moved |= move(file, new File(plugins, file.getName()));
		}
		return new ApplyResult(moved, deferred);
	}

	private static final AtomicBoolean PARK_ON_EXIT_ARMED = new AtomicBoolean();
	public static void parkOnExit() {
		if (!PARK_ON_EXIT_ARMED.compareAndSet(false, true))
			return;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				Set<String> disabledIDs = PluginGroupsConfig.get().disabledSet();
				if (!needsParking(disabledIDs))
					return;
				releasePluginArchives();
				apply(PluginLoader.INSTANCE.getPlugins(), disabledIDs);
			} catch (Exception _) {}
		}, "plugin-groups-park"));
	}

	private static boolean needsParking(Set<String> disabledIDs) {
		for (Plugin plugin : PluginLoader.INSTANCE.getPlugins())
			if (disabledIDs.contains(plugin.getID()) && isManageable(plugin))
				return true;
		return false;
	}

	/**
	 * A Java plugin lives in a child class loader whose parent is the thread context loader, so one of the loaders in
	 * this list is the loader of this very class. Closing it would break every class this file still needs after
	 * {@link #releasePluginArchives()} returns, so that loader (and anything above it) is left open.
	 */
	private static void releasePluginArchives() {
		ClassLoader ownLoader = PluginToggleService.class.getClassLoader();
		for (JavaPlugin javaPlugin : loadedJavaPlugins()) {
			ClassLoader loader = javaPlugin.getClass().getClassLoader();
			if (loader == ownLoader || loader == null || isAncestorOf(loader, ownLoader))
				continue;
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

	/** {@code ancestor} only reaches its own parent chain, so this never calls into a loader that may already be closed. */
	private static boolean isAncestorOf(ClassLoader ancestor, ClassLoader loader) {
		for (ClassLoader current = loader; current != null; current = current.getParent())
			if (current == ancestor)
				return true;
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
			if (plugin != null)
				result.add(plugin);
		}
		return result;
	}

	/**
	 * Reads {@code plugin.json} from the archive, going through the per-archive-version cache.
	 *
	 * <p>A miss (no descriptor, unreadable archive) is deliberately not cached: the same archive can be locked for a
	 * moment by an antivirus scan, and caching that {@code null} would keep the plugin reported as broken until the
	 * file's timestamp or size changed. Re-reading is cheap and keeps {@link #listDisabled()} honest.
	 */
	private static String readPluginJson(File archive) {
		ArchiveKey key = ArchiveKey.of(archive);
		synchronized (ARCHIVE_CACHE_LOCK) {
			String cached = JSON_CACHE.get(key);
			if (cached != null)
				return cached;
		}
		String raw;
		try {
			raw = ZipIO.readCodeInZip(archive, "plugin.json");
		} catch (Exception e) {
			raw = null;
		}
		if (raw == null)
			return null;
		synchronized (ARCHIVE_CACHE_LOCK) {
			if (JSON_CACHE.size() >= ARCHIVE_CACHE_LIMIT) {
				ZIP_CACHE.clear();
				JSON_CACHE.clear();
			}
			JSON_CACHE.put(key, raw);
		}
		return raw;
	}

	private static DisabledPlugin describe(File archive) {
		try {
			String raw = readPluginJson(archive);
			if (raw == null)
				return null;
			JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
			String id = text(root, "id", "");
			JsonObject info = root.has("info") && root.get("info").isJsonObject() ?
					root.getAsJsonObject("info") :
					new JsonObject();
			return new DisabledPlugin(id, text(info, "name", id), text(info, "version", ""),
					text(info, "author", ""), text(info, "description", ""), archive);
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
			if (file.isFile() && isZipArchive(file))
				archives.add(file);
		return archives;
	}

	/**
	 * Moves an archive between the plugins folder and {@code .disabled}.
	 *
	 * <p>Deliberately never replaces an existing target: two different plugins can share an archive name, and the old
	 * {@code REPLACE_EXISTING} silently destroyed one of them. A target that is in fact the same file (same absolute
	 * path, or the same file reached another way) already satisfies the request, so it counts as success and nothing
	 * is moved, which keeps the operation idempotent. Every other case reports failure so the caller can defer the
	 * plugin instead of losing data.
	 */
	private static boolean move(File from, File to) {
		try {
			File parent = to.getParentFile();
			if (parent != null)
				parent.mkdirs();
			if (isSameFile(from, to))
				return true;
			if (to.exists())
				return false;
			Files.move(from.toPath(), to.toPath());
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** Compares resolved paths so that a target reached through another route is still recognised as the same file. */
	private static boolean isSameFile(File from, File to) {
		File source = from.getAbsoluteFile();
		File target = to.getAbsoluteFile();
		if (source.equals(target))
			return true;
		try {
			return source.getCanonicalFile().equals(target.getCanonicalFile());
		} catch (IOException e) {
			return false;
		}
	}
}
