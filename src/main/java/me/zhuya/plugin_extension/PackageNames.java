package me.zhuya.plugin_extension;

import java.util.regex.Pattern;

final class PackageNames {
	private static final Pattern ILLEGAL_PACKAGE_CHARS = Pattern.compile("[^A-Za-z0-9_.]");
	private static final Pattern ILLEGAL_TYPE_CHARS = Pattern.compile("[^A-Za-z0-9_$.]");
	private static final Pattern REPEATED_DOTS = Pattern.compile("\\.{2,}");

	static String normalize(String value) {
		if (value == null) return "";
		String cleaned = value.trim().replace('\\', '/').replace('/', '.');
		cleaned = ILLEGAL_PACKAGE_CHARS.matcher(cleaned).replaceAll("");
		cleaned = REPEATED_DOTS.matcher(cleaned).replaceAll(".");
		return trimDots(cleaned);
	}

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
