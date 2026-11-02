package com.repomind.api.repo;

import com.repomind.api.ApiException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A validated github.com repository. Parsing is the only place a user-supplied URL becomes something we fetch (SSRF guard). */
public record RepoUrl(String owner, String name) {

	private static final Pattern PATTERN = Pattern.compile(
			"^(?i:https://github\\.com)/([A-Za-z0-9._-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?(?:/[^?#]*)?(?:[?#].*)?$");
	private static final String MESSAGE = "Enter a valid public GitHub repository URL (https://github.com/owner/repository).";

	public static RepoUrl parse(String input) {
		if (input == null) {
			throw new ApiException(400, MESSAGE);
		}
		String value = input.strip();
		Matcher m = PATTERN.matcher(value);
		if (hasControlCharacters(value) || hasParentSegment(value) || !m.matches() || isDots(m.group(1)) || isDots(m.group(2))) {
			throw new ApiException(400, MESSAGE);
		}
		return new RepoUrl(m.group(1), m.group(2));
	}

	private static boolean hasControlCharacters(String s) {
		return s.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
	}

	private static boolean hasParentSegment(String s) {
		return java.util.Arrays.asList(s.split("[?#]", 2)[0].split("/")).contains("..");
	}

	private static boolean isDots(String s) {
		return s.chars().allMatch(c -> c == '.');
	}

	public String canonical() {
		return "https://github.com/" + owner + "/" + name;
	}
}
