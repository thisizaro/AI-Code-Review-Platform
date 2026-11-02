package com.repomind.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Tiny in-JVM stand-ins for GitHub and the Python AI service, so integration tests need no network. */
public final class StubServers {

	public static final AtomicInteger zipDownloads = new AtomicInteger();
	public static final AtomicReference<String> lastGithubAuth = new AtomicReference<>();
	public static final Map<String, String> pythonBodies = new ConcurrentHashMap<>();

	private static HttpServer github;
	private static HttpServer python;

	private StubServers() {}

	public static synchronized void start() throws IOException {
		if (github != null) {
			return;
		}
		github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		github.createContext("/repos/", StubServers::githubRepos);
		github.createContext("/codeload/", StubServers::githubZip);
		github.start();

		python = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		python.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r);
			t.setDaemon(true);
			return t;
		}));
		python.createContext("/internal/analyze", StubServers::pythonAnalyze);
		python.createContext("/internal/match", StubServers::pythonMatch);
		python.createContext("/internal/ask", StubServers::pythonAsk);
		python.start();
	}

	public static String githubUrl() {
		return "http://127.0.0.1:" + github.getAddress().getPort();
	}

	public static String pythonUrl() {
		return "http://127.0.0.1:" + python.getAddress().getPort();
	}

	private static void githubRepos(HttpExchange ex) throws IOException {
		lastGithubAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
		String[] p = ex.getRequestURI().getPath().split("/"); // "", repos, owner, repo, ...
		String repo = p.length > 3 ? p[3] : "";
		switch (repo) {
			case "ghost" -> send(ex, 404, "application/json", "{\"message\":\"Not Found\"}");
			case "busy" -> {
				ex.getResponseHeaders().add("X-RateLimit-Remaining", "0");
				send(ex, 403, "application/json", "{\"message\":\"API rate limit exceeded\"}");
			}
			case "boom" -> send(ex, 500, "text/plain", "oops");
			default -> {
				if (ex.getRequestURI().getPath().endsWith("/commits/HEAD")) {
					send(ex, 200, "text/plain", "sha123");
				} else {
					ex.getResponseHeaders().add("Location", githubUrl() + "/codeload/acme-demo.zip");
					ex.sendResponseHeaders(302, -1);
					ex.close();
				}
			}
		}
	}

	private static void githubZip(HttpExchange ex) throws IOException {
		zipDownloads.incrementAndGet();
		byte[] zip = ZipTestUtil.zip(ZipTestUtil.files(
				"acme-demo-sha123/src/app.py", "def main():\n    return 1\n",
				"acme-demo-sha123/tests/test_app.py", "def test_main():\n    pass\n",
				"acme-demo-sha123/README.md", "# demo\n",
				"acme-demo-sha123/node_modules/lib/index.js", "ignored"));
		ex.getResponseHeaders().add("Content-Type", "application/zip");
		ex.sendResponseHeaders(200, zip.length);
		ex.getResponseBody().write(zip);
		ex.close();
	}

	private static void pythonAnalyze(HttpExchange ex) throws IOException {
		String body = record(ex);
		send(ex, 200, "application/json", """
				{"issues":[
				  {"severity":"Medium","title":"Long function: big (70 lines)","file":"src/app.py","line":1,"detail":"split it"},
				  {"severity":"Medium","title":"Bare except","file":"src/app.py","line":9,"detail":"catch specific"}],
				 "security_findings":[
				  {"severity":"High","title":"Hardcoded secret","file":"src/app.py","line":3,"detail":"move to env"},
				  {"severity":"High","title":"Use of eval/exec","file":"src/app.py","line":5,"detail":"avoid eval"}],
				 "functions_analyzed":7}""");
	}

	private static void pythonMatch(HttpExchange ex) throws IOException {
		String body = record(ex);
		if (body.contains("FAIL500")) {
			send(ex, 500, "application/json", "{\"detail\":\"boom\"}");
		} else if (body.contains("SLOW")) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			send(ex, 200, "application/json", "{}");
		} else if (body.contains("NOSKILLS")) {
			send(ex, 400, "application/json", "{\"detail\":\"No skills found in job description\"}");
		} else {
			send(ex, 200, "application/json",
					"{\"match_score\":76,\"matched_skills\":[\"python\"],\"missing_skills\":[\"aws\"],\"explanation\":\"Decent fit.\"}");
		}
	}

	private static void pythonAsk(HttpExchange ex) throws IOException {
		record(ex);
		send(ex, 200, "application/json", "{\"answer\":\"Routes live in src/app.py\",\"sources\":[\"src/app.py\"]}");
	}

	private static String record(HttpExchange ex) throws IOException {
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		pythonBodies.put(ex.getRequestURI().getPath(), body);
		return body;
	}

	private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", type);
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
		ex.close();
	}
}
