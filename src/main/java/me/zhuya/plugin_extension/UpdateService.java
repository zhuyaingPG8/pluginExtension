package me.zhuya.plugin_extension;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcreator.io.net.WebIO;
import net.mcreator.plugin.Plugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UpdateService {
	private static final Logger LOG = LogManager.getLogger("pluginExtension");

	record UpdateInfo(String id, String currentVersion, String availableVersion, List<String> changes,
					  int pageID) {}

	record Result(Map<String, UpdateInfo> updates, int checked, int failed, int unsupported) {}

	private record Source(String id, String version, String updateURL, int pageID) {}

	static Result check(List<Plugin> loadedPlugins, List<PluginToggleService.DisabledPlugin> parkedPlugins) {
		Map<String, Source> sources = new LinkedHashMap<>();
		for (Plugin plugin : loadedPlugins) {
			if (plugin.isBuiltin()) continue;
			if (plugin.getInfo() == null) continue;
			String updateURL = plugin.getInfo().getUpdateJSONURL();
			if (PluginToggleService.isSelf(plugin.getID()) && (updateURL == null || updateURL.isBlank())) continue;
			sources.putIfAbsent(plugin.getID(),
					new Source(plugin.getID(), plugin.getPluginVersion(), updateURL,
							plugin.getInfo().getPluginPageID()));
		}
		for (PluginToggleService.DisabledPlugin plugin : parkedPlugins) {
			sources.putIfAbsent(plugin.id(), new Source(plugin.id(), plugin.version(), plugin.updateJSONURL(),
					plugin.pluginPageID()));
		}

		Map<String, UpdateInfo> updates = new LinkedHashMap<>();
		int checked = 0, failed = 0, unsupported = 0;
		for (Source source : sources.values()) {
			if (source.updateURL() == null || source.updateURL().isBlank()) {
				unsupported++;
				continue;
			}
			checked++;
			try {
				String raw = WebIO.readURLToString(source.updateURL());
				if (raw.isBlank()) {
					failed++;
					continue;
				}
				UpdateInfo info = parse(source, raw);
				if (info != null) updates.put(source.id(), info);
			} catch (Exception e) {
				failed++;
				LOG.warn("Failed to check updates for plugin {}: {}", source.id(), e.toString());
			}
		}
		return new Result(updates, checked, failed, unsupported);
	}

	private static UpdateInfo parse(Source source, String raw) {
		JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
		JsonElement entry = root.get(source.id());
		if (entry == null || !entry.isJsonObject()) return null;
		JsonObject data = entry.getAsJsonObject();
		if (!data.has("latest") || data.get("latest").isJsonNull()) return null;
		String latest = data.get("latest").getAsString();
		if (!isNewer(latest, source.version())) return null;
		List<String> changes = new ArrayList<>();
		if (data.has("changes") && data.get("changes").isJsonArray()) {
			JsonArray array = data.getAsJsonArray("changes");
			for (JsonElement element : array)
				if (!element.isJsonNull()) changes.add(element.getAsString());
		}
		return new UpdateInfo(source.id(), source.version(), latest, List.copyOf(changes), source.pageID());
	}

	static boolean isNewer(String remote, String current) {
		if (remote == null || remote.isBlank()) return false;
		if (current == null || current.isBlank()) return true;
		if (remote.equals(current)) return false;
		int[] remoteParts = numericParts(remote);
		int[] currentParts = numericParts(current);
		if (remoteParts == null || currentParts == null) return true;
		for (int i = 0; i < Math.max(remoteParts.length, currentParts.length); i++) {
			int r = i < remoteParts.length ? remoteParts[i] : 0;
			int c = i < currentParts.length ? currentParts[i] : 0;
			if (r != c) return r > c;
		}
		return false;
	}

	private static int[] numericParts(String version) {
		String[] pieces = version.split("[^0-9]+");
		List<Integer> numbers = new ArrayList<>();
		for (String piece : pieces) {
			if (piece.isEmpty()) continue;
			try {
				numbers.add(Integer.parseInt(piece));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (numbers.isEmpty()) return null;
		int[] result = new int[numbers.size()];
		for (int i = 0; i < result.length; i++) result[i] = numbers.get(i);
		return result;
	}
}
