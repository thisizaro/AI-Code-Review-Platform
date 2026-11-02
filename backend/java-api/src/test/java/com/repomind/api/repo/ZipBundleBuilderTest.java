package com.repomind.api.repo;

import static com.repomind.api.ZipTestUtil.files;
import static com.repomind.api.ZipTestUtil.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repomind.api.ApiException;
import com.repomind.api.RepomindProperties.Limits;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZipBundleBuilderTest {

	private static final Limits DEFAULT = new Limits(300, 200 * 1024, 1_500_000);

	private ExtractedFiles extract(byte[] zip, Limits limits) {
		return new ZipBundleBuilder(limits).build(new ByteArrayInputStream(zip));
	}

	private List<String> paths(ExtractedFiles result) {
		return result.files().stream().map(RepoFile::path).toList();
	}

	@Test
	void stripsTheRootFolderAndReadsContent() {
		ExtractedFiles r = extract(zip(files("acme-demo-abc/", null, "acme-demo-abc/src/App.java", "class App {}")), DEFAULT);
		assertThat(r.files()).hasSize(1);
		RepoFile f = r.files().get(0);
		assertThat(f.path()).isEqualTo("src/App.java");
		assertThat(f.content()).isEqualTo("class App {}");
		assertThat(f.language()).isEqualTo("java");
		assertThat(f.size()).isEqualTo(12);
		assertThat(r.truncated()).isFalse();
	}

	@Test
	void skipsVendoredBuildAndGeneratedPaths() {
		byte[] z = zip(files(
				"r/node_modules/lib/index.js", "x", "r/.git/config", "x", "r/dist/app.js", "x", "r/target/A.class", "x",
				"r/build/out.js", "x", "r/vendor/v.go", "x", "r/__pycache__/a.py", "x", "r/.venv/lib.py", "x",
				"r/package-lock.json", "{}", "r/yarn.lock", "x", "r/app.min.js", "x", "r/src/keep.js", "ok"));
		ExtractedFiles r = extract(z, DEFAULT);
		assertThat(paths(r)).containsExactly("src/keep.js");
		assertThat(r.truncated()).isFalse();
	}

	@Test
	void keepsTextSourceAndConfigAndSkipsBinaryFormats() {
		byte[] z = zip(files(
				"r/logo.png", "x", "r/data.bin", "x", "r/Dockerfile", "FROM x", "r/.env.example", "A=1",
				"r/README.md", "# hi", "r/pom.xml", "<p/>", "r/notes.txt", "n", "r/Makefile", "all:"));
		assertThat(paths(extract(z, DEFAULT))).containsExactlyInAnyOrder("Dockerfile", ".env.example", "README.md", "pom.xml", "notes.txt", "Makefile");
	}

	@Test
	void skipsFilesContainingBinaryNulBytes() {
		byte[] z = zip(files("r/a.py", new byte[] {'a', 0, 'b'}, "r/b.py", "print(1)"));
		assertThat(paths(extract(z, DEFAULT))).containsExactly("b.py");
	}

	@Test
	void skipsFilesOverThePerFileLimitAndReportsTruncation() {
		byte[] z = zip(files("r/big.py", "x".repeat(300), "r/small.py", "y"));
		ExtractedFiles r = extract(z, new Limits(300, 100, 1_000_000));
		assertThat(paths(r)).containsExactly("small.py");
		assertThat(r.truncated()).isTrue();
	}

	@Test
	void keepsShallowFilesFirstWhenOverTheFileCountLimit() {
		byte[] z = zip(files("r/dir/sub/c.py", "1", "r/a.py", "1", "r/dir/b.py", "1", "r/d.py", "1", "r/e.py", "1"));
		ExtractedFiles r = extract(z, new Limits(2, 1000, 1_000_000));
		assertThat(paths(r)).containsExactly("a.py", "d.py");
		assertThat(r.truncated()).isTrue();
	}

	@Test
	void stopsAtTheTotalSizeLimit() {
		byte[] z = zip(files("r/a.txt", "0123456789", "r/b.txt", "0123456789", "r/c.txt", "0123456789"));
		ExtractedFiles r = extract(z, new Limits(100, 1000, 25));
		assertThat(paths(r)).containsExactly("a.txt", "b.txt");
		assertThat(r.truncated()).isTrue();
	}

	@Test
	void ignoresPathTraversalEntries() {
		byte[] z = zip(files("r/../../evil.py", "x", "r/ok.py", "y"));
		assertThat(paths(extract(z, DEFAULT))).containsExactly("ok.py");
	}

	@Test
	void emptyArchiveGivesNoFiles() {
		ExtractedFiles r = extract(zip(files("r/", null)), DEFAULT);
		assertThat(r.files()).isEmpty();
		assertThat(r.truncated()).isFalse();
	}

	@Test
	void garbageInputIsA502() {
		assertThatThrownBy(() -> new ZipBundleBuilder(DEFAULT).build(new ByteArrayInputStream("not a zip".getBytes())))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(502));
	}
}
