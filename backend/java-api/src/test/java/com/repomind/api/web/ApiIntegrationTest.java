package com.repomind.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.repomind.api.StubServers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

	private static final String DEMO = "https://github.com/acme/demo";

	@BeforeAll
	static void startStubs() throws IOException {
		StubServers.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) throws IOException {
		StubServers.start();
		registry.add("repomind.github.base-url", StubServers::githubUrl);
		registry.add("repomind.github.token", () -> "tok");
		registry.add("repomind.python.base-url", StubServers::pythonUrl);
		registry.add("repomind.python.timeout-seconds", () -> "1");
	}

	@Autowired
	TestRestTemplate rest;

	@LocalServerPort
	int port;

	private ResponseEntity<JsonNode> post(String path, Map<String, ?> body) {
		return rest.postForEntity(path, body, JsonNode.class);
	}

	// ---- analyze ----

	@Test
	void analyzeReturnsFindingsMetricsAndAComputedScore() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", DEMO + ".git"));
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		JsonNode body = r.getBody();
		assertThat(body.get("quality_score").asInt()).isEqualTo(92);
		assertThat(body.get("issues")).hasSize(2);
		assertThat(body.get("issues").get(0).get("file").asText()).isEqualTo("src/app.py");
		assertThat(body.get("security_findings").get(0).get("severity").asText()).isEqualTo("High");
		JsonNode metrics = body.get("metrics");
		assertThat(metrics.get("files_analyzed").asInt()).isEqualTo(3);
		assertThat(metrics.get("test_files").asInt()).isEqualTo(1);
		assertThat(metrics.get("functions_analyzed").asInt()).isEqualTo(7);
		assertThat(metrics.get("truncated").asBoolean()).isFalse();
	}

	@Test
	void analyzeSendsTheFilteredBundleToPythonAndAuthenticatesToGithub() {
		post("/api/repositories/analyze", Map.of("repository_url", DEMO));
		String sent = StubServers.pythonBodies.get("/internal/analyze");
		assertThat(sent).contains("\"sha\":\"sha123\"").contains("\"owner\":\"acme\"").contains("src/app.py")
				.contains("def main()").doesNotContain("node_modules");
		assertThat(StubServers.lastGithubAuth.get()).isEqualTo("Bearer tok");
	}

	@Test
	void analyzeRejectsBadUrlsWith400AndAMessage() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", "https://evil.com/a/b"));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).contains("GitHub repository URL");
	}

	@Test
	void analyzeWithMissingUrlIs400() {
		assertThat(post("/api/repositories/analyze", Map.of()).getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void unknownRepositoryIs404() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", "https://github.com/acme/ghost"));
		assertThat(r.getStatusCode().value()).isEqualTo(404);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("not found");
	}

	@Test
	void githubRateLimitIs429() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", "https://github.com/acme/busy"));
		assertThat(r.getStatusCode().value()).isEqualTo(429);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("rate limit");
	}

	@Test
	void githubServerErrorIs502() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", "https://github.com/acme/boom"));
		assertThat(r.getStatusCode().value()).isEqualTo(502);
	}

	@Test
	void repeatedRequestsDownloadTheArchiveOnce() {
		int before = StubServers.zipDownloads.get();
		post("/api/repositories/analyze", Map.of("repository_url", "https://github.com/acme/cached"));
		post("/api/learning/ask", Map.of("repository_url", "https://github.com/acme/cached", "question", "what is this?"));
		assertThat(StubServers.zipDownloads.get() - before).isEqualTo(1);
	}

	// ---- matching ----

	@Test
	void matchingPassesThroughPythonResultAndSendsTheJobDescription() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "Python dev"));
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		assertThat(r.getBody().get("match_score").asInt()).isEqualTo(76);
		assertThat(r.getBody().get("matched_skills").get(0).asText()).isEqualTo("python");
		assertThat(r.getBody().get("missing_skills").get(0).asText()).isEqualTo("aws");
		assertThat(r.getBody().get("explanation").asText()).isEqualTo("Decent fit.");
		assertThat(StubServers.pythonBodies.get("/internal/match")).contains("\"job_description\":\"Python dev\"");
	}

	@Test
	void matchingRequiresAJobDescription() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "   "));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("job description");
	}

	@Test
	void matchingRejectsAnOversizedJobDescription() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "x".repeat(10_001)));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("too long");
	}

	@Test
	void pythonClientErrorsAreForwardedAs400() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "NOSKILLS"));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).isEqualTo("No skills found in job description");
	}

	@Test
	void pythonServerErrorIs502() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "FAIL500"));
		assertThat(r.getStatusCode().value()).isEqualTo(502);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("AI service");
	}

	@Test
	void pythonTimeoutIs504() {
		ResponseEntity<JsonNode> r = post("/api/matching/analyze", Map.of("repository_url", DEMO, "job_description", "SLOW"));
		assertThat(r.getStatusCode().value()).isEqualTo(504);
	}

	// ---- learning ----

	@Test
	void learningReturnsAnswerAndSources() {
		ResponseEntity<JsonNode> r = post("/api/learning/ask", Map.of("repository_url", DEMO, "question", "Where are routes?"));
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		assertThat(r.getBody().get("answer").asText()).isEqualTo("Routes live in src/app.py");
		assertThat(r.getBody().get("sources").get(0).asText()).isEqualTo("src/app.py");
		assertThat(StubServers.pythonBodies.get("/internal/ask")).contains("\"question\":\"Where are routes?\"");
	}

	@Test
	void learningRequiresAQuestion() {
		ResponseEntity<JsonNode> r = post("/api/learning/ask", Map.of("repository_url", DEMO, "question", ""));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("question");
	}

	// ---- misc ----

	@Test
	void healthEndpointIsUp() {
		ResponseEntity<JsonNode> r = rest.getForEntity("/api/health", JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		assertThat(r.getBody().get("status").asText()).isEqualTo("ok");
	}

	@Test
	void malformedJsonIs400WithAMessage() {
		org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
		h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		ResponseEntity<JsonNode> r = rest.postForEntity("/api/repositories/analyze", new org.springframework.http.HttpEntity<>("{not json", h), JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(400);
		assertThat(r.getBody().get("message").asText()).isNotBlank();
	}

	@Test
	void wrongContentTypeIs415NotA500() {
		org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
		h.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
		ResponseEntity<JsonNode> r = rest.postForEntity("/api/repositories/analyze", new org.springframework.http.HttpEntity<>("{\"repository_url\":\"" + DEMO + "\"}", h), JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(415);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("json");
	}

	@Test
	void wrongMethodIs405() {
		ResponseEntity<JsonNode> r = rest.getForEntity("/api/repositories/analyze", JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(405);
		assertThat(r.getBody().get("message").asText()).isNotBlank();
		assertThat(rest.postForEntity("/api/health", Map.of(), JsonNode.class).getStatusCode().value()).isEqualTo(405);
	}

	@Test
	void unknownPathIs404() {
		ResponseEntity<JsonNode> r = rest.getForEntity("/api/nope", JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(404);
		assertThat(r.getBody().get("message").asText()).isNotBlank();
	}

	@Test
	void oversizedRequestBodyIs413() {
		ResponseEntity<JsonNode> r = post("/api/repositories/analyze", Map.of("repository_url", DEMO, "pad", "x".repeat(70_000)));
		assertThat(r.getStatusCode().value()).isEqualTo(413);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("too large");
	}

	@Test
	void wrongTypedFieldsAreRejectedInsteadOfCoerced() {
		ResponseEntity<JsonNode> r = post("/api/learning/ask", Map.of("repository_url", DEMO, "question", 5));
		assertThat(r.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void corsAllowsTheFrontendOriginOnly() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> ok = client.send(preflight("http://localhost:5173"), HttpResponse.BodyHandlers.ofString());
		assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
		HttpResponse<String> bad = client.send(preflight("http://evil.example"), HttpResponse.BodyHandlers.ofString());
		assertThat(bad.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
	}

	private HttpRequest preflight(String origin) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/repositories/analyze"))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.header("Origin", origin)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type")
				.build();
	}
}
