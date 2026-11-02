package com.repomind.api.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repomind.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RepoUrlTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"https://github.com/acme/demo",
			"https://github.com/acme/demo/",
			"https://github.com/acme/demo.git",
			"https://github.com/acme/demo/tree/main/src",
			"https://github.com/acme/demo/blob/main/README.md",
			"https://github.com/acme/demo?tab=readme",
			"  https://github.com/acme/demo  "})
	void normalizesAcceptedForms(String input) {
		RepoUrl url = RepoUrl.parse(input);
		assertThat(url.owner()).isEqualTo("acme");
		assertThat(url.name()).isEqualTo("demo");
		assertThat(url.canonical()).isEqualTo("https://github.com/acme/demo");
	}

	@Test
	void keepsDotsAndDashesInNames() {
		RepoUrl url = RepoUrl.parse("https://github.com/my-org/my.repo-name_2");
		assertThat(url.owner()).isEqualTo("my-org");
		assertThat(url.name()).isEqualTo("my.repo-name_2");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			"http://github.com/acme/demo",
			"https://evil.com/acme/demo",
			"https://github.com.evil.com/acme/demo",
			"https://github.com@evil.com/acme/demo",
			"https://github.com/acme",
			"https://github.com/",
			"https://github.com/acme/../etc",
			"https://github.com/ac me/demo",
			"https://github.com/acme/demo/../../x",
			"https://github.com/acme/demo/tree/main/../../x",
			"https://github.com/acme/demo\u0000",
			"https://github.com/acme/demo\nextra",
			"git@github.com:acme/demo.git",
			"not a url"})
	void rejectsEverythingElseWith400(String input) {
		assertThatThrownBy(() -> RepoUrl.parse(input))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(400))
				.hasMessageContaining("GitHub repository URL");
	}

	@Test
	void hostIsCaseInsensitiveAndCanonicalFormIsLowercaseHost() {
		assertThat(RepoUrl.parse("https://GitHub.com/Acme/Demo").canonical()).isEqualTo("https://github.com/Acme/Demo");
	}

	@Test
	void rejectsRepositoryNamesLongerThanGitHubAllows() {
		assertThatThrownBy(() -> RepoUrl.parse("https://github.com/acme/" + "a".repeat(101))).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(400));
		assertThat(RepoUrl.parse("https://github.com/acme/" + "a".repeat(100)).name()).hasSize(100);
	}
}
