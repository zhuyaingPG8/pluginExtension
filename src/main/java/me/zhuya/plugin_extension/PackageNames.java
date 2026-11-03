package me.zhuya.plugin_extension;

import java.util.regex.Pattern;

/**
 * Pure string helpers for the package/type name fields of {@link PackagedCodeGUI}.
 *
 * <p>This class intentionally has no Swing or MCreator dependency so it can be unit-tested
 * standalone. The patterns are compiled once and reused: the GUI calls {@link #normalize(String)}
 * on every keystroke (via the editor key listener), so compiling them per call was wasteful.
 */
final class PackageNames {
	private static final Pattern ILLEGAL_PACKAGE_CHARS = Pattern.compile("[^A-Za-z0-9_.]");
	private static final Pattern ILLEGAL_TYPE_CHARS = Pattern.compile("[^A-Za-z0-9_$.]");
	private static final Pattern REPEATED_DOTS = Pattern.compile("\\.{2,}");

	private PackageNames() {
	}

	/**
	 * Normalizes a package name: '/' and '\' become '.', characters outside {@code [A-Za-z0-9_.]}
	 * are dropped, repeated dots collapse and leading/trailing dots are trimmed.
	 */
	static String normalize(String value) {
		if (value == null)
			return "";
		String cleaned = value.trim().replace('\\', '/').replace('/', '.');
		cleaned = ILLEGAL_PACKAGE_CHARS.matcher(cleaned).replaceAll("");
		cleaned = REPEATED_DOTS.matcher(cleaned).replaceAll(".");
		return trimDots(cleaned);
	}

	/**
	 * Normalizes a comma separated list of type names (super class / interfaces). Like
	 * {@link #normalize(String)} but additionally allows '$' for nested type references.
	 */
	static String normalizeTypeList(String value) {
		if (value == null || value.isBlank())
			return "";
		StringBuilder cleaned = new StringBuilder();
		for (String part : value.split(",")) {
			String name = part.trim().replace('\\', '/').replace('/', '.');
			name = ILLEGAL_TYPE_CHARS.matcher(name).replaceAll("");
			name = REPEATED_DOTS.matcher(name).replaceAll(".");
			name = trimDots(name);
			if (name.isEmpty())
				continue;
			if (!cleaned.isEmpty())
				cleaned.append(", ");
			cleaned.append(name);
		}
		return cleaned.toString();
	}

	/** Removes leading and trailing '.' characters. */
	static String trimDots(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && value.charAt(start) == '.')
			start++;
		while (end > start && value.charAt(end - 1) == '.')
			end--;
		return value.substring(start, end);
	}
}
