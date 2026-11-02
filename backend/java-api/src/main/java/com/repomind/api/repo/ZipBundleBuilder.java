package com.repomind.api.repo;

import com.repomind.api.ApiException;
import com.repomind.api.RepomindProperties.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Turns a GitHub zipball into the filtered list of text files we analyze. Nothing from the archive is ever executed. */
public class ZipBundleBuilder {

	private static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", ".git", "dist", "build", "target", "vendor",
			"__pycache__", ".venv", "venv", ".idea", ".vscode", ".next", "coverage", ".gradle");
	private static final Set<String> EXCLUDED_NAMES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml",
			"poetry.lock", "pipfile.lock", "cargo.lock", "composer.lock", "go.sum", "gradle.lockfile");
	private static final Set<String> EXCLUDED_SUFFIXES = Set.of(".min.js", ".min.css", ".map");
	private static final Set<String> EXTENSIONS = Set.of("py", "java", "js", "jsx", "ts", "tsx", "mjs", "cjs", "go", "rs",
			"kt", "kts", "php", "rb", "cs", "c", "h", "cpp", "hpp", "swift", "scala", "sh", "bash", "html", "css", "scss",
			"sql", "md", "txt", "yml", "yaml", "json", "xml", "toml", "properties", "gradle", "ini", "cfg", "env", "conf");
	private static final Set<String> NAMES = Set.of("dockerfile", "makefile", "jenkinsfile", "gemfile", "pipfile",
			".env.example", ".env.sample");
	private static final Map<String, String> LANGUAGES = Map.ofEntries(Map.entry("py", "python"),
			Map.entry("java", "java"), Map.entry("js", "javascript"), Map.entry("jsx", "javascript"),
			Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"), Map.entry("md", "markdown"),
			Map.entry("yml", "yaml"), Map.entry("kt", "kotlin"), Map.entry("rb", "ruby"), Map.entry("rs", "rust"));

	private record Candidate(String path, ZipEntry entry, int depth) {}

	private final Limits limits;

	public ZipBundleBuilder(Limits limits) {
		this.limits = limits;
	}

	public ExtractedFiles build(InputStream zip) {
		Path temp = null;
		try {
			temp = Files.createTempFile("repomind-", ".zip");
			Files.copy(zip, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			try (ZipFile file = new ZipFile(temp.toFile())) {
				return extract(file);
			}
		} catch (ZipException e) {
			throw new ApiException(502, "Could not read the repository archive from GitHub.");
		} catch (IOException e) {
			throw new ApiException(502, "Could not read the repository archive from GitHub.");
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException ignored) {
					// best effort cleanup of a temp file
				}
			}
		}
	}

	private ExtractedFiles extract(ZipFile file) throws IOException {
		List<Candidate> candidates = new ArrayList<>();
		Enumeration<? extends ZipEntry> entries = file.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String path = stripRoot(entry);
			if (path != null && isWanted(path)) {
				candidates.add(new Candidate(path, entry, (int) path.chars().filter(c -> c == '/').count()));
			}
		}
		candidates.sort(Comparator.comparingInt(Candidate::depth).thenComparing(Candidate::path));

		List<RepoFile> kept = new ArrayList<>();
		long total = 0;
		boolean truncated = false;
		for (Candidate c : candidates) {
			if (kept.size() >= limits.maxFiles()) {
				truncated = true;
				break;
			}
			if (c.entry().getSize() > limits.maxFileBytes()) {
				truncated = true;
				continue;
			}
			byte[] bytes = readBounded(file, c.entry());
			if (bytes == null) {
				truncated = true;
				continue;
			}
			if (isBinary(bytes)) {
				continue;
			}
			if (total + bytes.length > limits.maxTotalBytes()) {
				truncated = true;
				continue;
			}
			total += bytes.length;
			kept.add(new RepoFile(c.path(), languageOf(c.path()), bytes.length, new String(bytes, StandardCharsets.UTF_8)));
		}
		return new ExtractedFiles(kept, truncated);
	}

	/** Returns the path without the archive's root folder, or null for directories and unsafe paths. */
	private static String stripRoot(ZipEntry entry) {
		if (entry.isDirectory()) {
			return null;
		}
		String name = entry.getName().replace('\\', '/');
		if (name.startsWith("/")) {
			return null;
		}
		String[] segments = name.split("/");
		for (String s : segments) {
			if (s.equals("..")) {
				return null;
			}
		}
		if (segments.length < 2) {
			return null;
		}
		return String.join("/", java.util.Arrays.copyOfRange(segments, 1, segments.length));
	}

	private static boolean isWanted(String path) {
		String[] segments = path.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			if (EXCLUDED_DIRS.contains(segments[i])) {
				return false;
			}
		}
		String name = segments[segments.length - 1];
		String lower = name.toLowerCase();
		if (EXCLUDED_NAMES.contains(lower) || EXCLUDED_SUFFIXES.stream().anyMatch(lower::endsWith)) {
			return false;
		}
		return NAMES.contains(lower) || EXTENSIONS.contains(extensionOf(lower));
	}

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

	private static String languageOf(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
		if (name.equals("dockerfile")) {
			return "dockerfile";
		}
		String ext = extensionOf(name);
		return ext.isEmpty() ? "text" : LANGUAGES.getOrDefault(ext, ext);
	}

	private byte[] readBounded(ZipFile file, ZipEntry entry) throws IOException {
		try (InputStream in = file.getInputStream(entry)) {
			byte[] bytes = in.readNBytes((int) limits.maxFileBytes() + 1);
			return bytes.length > limits.maxFileBytes() ? null : bytes;
		}
	}

	private static boolean isBinary(byte[] bytes) {
		for (byte b : bytes) {
			if (b == 0) {
				return true;
			}
		}
		return false;
	}
}
