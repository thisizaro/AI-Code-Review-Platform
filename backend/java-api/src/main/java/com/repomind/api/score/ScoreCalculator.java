package com.repomind.api.score;

import com.repomind.api.web.Finding;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quality score = 100 - penalty + test bonus, clamped to 0..100.
 * penalty: 6 per High, 3 per Medium, 1 per Low finding, capped at 60.
 * bonus: up to 10 points, full marks when 20 percent of analyzed files are tests.
 */
public final class ScoreCalculator {

	private static final Set<String> TEST_DIRS = Set.of("test", "tests", "__tests__", "spec", "specs");
	private static final Pattern TEST_NAME = Pattern.compile(
			"(?i)^test_.*|.*_test\\.[a-z0-9]+|.*_spec\\.[a-z0-9]+|.*\\.(test|spec)\\.[a-z0-9]+");
	private static final Pattern JVM_TEST_NAME = Pattern.compile(".*Tests?\\.(java|kt|cs)");

	private ScoreCalculator() {}

	public static int qualityScore(List<Finding> issues, List<Finding> security, int testFiles, int filesAnalyzed) {
		int penalty = 0;
		for (Finding f : concat(issues, security)) {
			penalty += switch (f.severity() == null ? "" : f.severity().toLowerCase()) {
				case "high" -> 6;
				case "medium" -> 3;
				case "low" -> 1;
				default -> 0;
			};
		}
		penalty = Math.min(60, penalty);
		double bonus = Math.min(10.0, 50.0 * testFiles / Math.max(1, filesAnalyzed));
		long score = Math.round(100 - penalty + bonus);
		return (int) Math.max(0, Math.min(100, score));
	}

	public static int countTestFiles(List<String> paths) {
		int count = 0;
		for (String path : paths) {
			if (isTestFile(path)) {
				count++;
			}
		}
		return count;
	}

	private static boolean isTestFile(String path) {
		String[] segments = path.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			if (TEST_DIRS.contains(segments[i].toLowerCase())) {
				return true;
			}
		}
		String name = segments[segments.length - 1];
		return TEST_NAME.matcher(name).matches() || JVM_TEST_NAME.matcher(name).matches();
	}

	private static List<Finding> concat(List<Finding> a, List<Finding> b) {
		java.util.ArrayList<Finding> all = new java.util.ArrayList<>(a);
		all.addAll(b);
		return all;
	}
}
