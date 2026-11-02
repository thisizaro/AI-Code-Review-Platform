package com.repomind.api.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.repomind.api.web.Finding;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreCalculatorTest {

	private static Finding f(String severity) {
		return new Finding(severity, "t", "a.py", 1, "d");
	}

	private static List<Finding> many(String severity, int n) {
		return Collections.nCopies(n, f(severity));
	}

	@Test
	void perfectRepoScores100() {
		assertThat(ScoreCalculator.qualityScore(List.of(), List.of(), 0, 10)).isEqualTo(100);
	}

	@Test
	void weightsSeverities() {
		// 6 (high) + 3 (medium) + 1 (low) = 10 penalty, no tests
		assertThat(ScoreCalculator.qualityScore(List.of(f("Medium"), f("Low")), List.of(f("High")), 0, 10)).isEqualTo(90);
	}

	@Test
	void infoFindingsDoNotCost() {
		assertThat(ScoreCalculator.qualityScore(many("Info", 50), List.of(), 0, 10)).isEqualTo(100);
	}

	@Test
	void severityMatchingIsCaseInsensitive() {
		assertThat(ScoreCalculator.qualityScore(List.of(f("high")), List.of(), 0, 10)).isEqualTo(94);
	}

	@Test
	void penaltyIsCappedAt60() {
		assertThat(ScoreCalculator.qualityScore(many("High", 20), List.of(), 0, 10)).isEqualTo(40);
	}

	@Test
	void testFilesAddUpToTenBonusPoints() {
		// penalty 6, bonus 50 * 1/10 = 5
		assertThat(ScoreCalculator.qualityScore(List.of(f("High")), List.of(), 1, 10)).isEqualTo(99);
		// bonus capped at 10, penalty 30 -> 80
		assertThat(ScoreCalculator.qualityScore(many("High", 5), List.of(), 5, 10)).isEqualTo(80);
	}

	@Test
	void scoreIsClampedAndSafeWithZeroFiles() {
		assertThat(ScoreCalculator.qualityScore(List.of(), List.of(), 0, 0)).isEqualTo(100);
		assertThat(ScoreCalculator.qualityScore(List.of(), List.of(), 5, 0)).isEqualTo(100);
	}

	@Test
	void countsTestFilesBySegmentsAndNamingConventions() {
		List<String> paths = List.of(
				"src/test/java/AppTest.java", "tests/test_app.py", "src/App.test.jsx", "web/__tests__/x.js",
				"pkg/foo_test.go", "spec/models/user_spec.rb", "src/Thing.spec.ts", "src/UserTests.java");
		assertThat(ScoreCalculator.countTestFiles(paths)).isEqualTo(8);
	}

	@Test
	void doesNotCountLookalikeNames() {
		List<String> paths = List.of("src/latest/a.py", "contest.py", "src/attestation.java", "docs/protest.md", "README.md");
		assertThat(ScoreCalculator.countTestFiles(paths)).isZero();
	}
}
