package com.repomind.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.repomind.api.StubServers;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PythonDownIntegrationTest {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) throws IOException {
		StubServers.start();
		registry.add("repomind.github.base-url", StubServers::githubUrl);
		registry.add("repomind.python.base-url", () -> "http://127.0.0.1:1");
	}

	@Autowired
	TestRestTemplate rest;

	@Test
	void unreachableAiServiceIs502WithAClearMessage() {
		ResponseEntity<JsonNode> r = rest.postForEntity("/api/repositories/analyze",
				Map.of("repository_url", "https://github.com/acme/demo"), JsonNode.class);
		assertThat(r.getStatusCode().value()).isEqualTo(502);
		assertThat(r.getBody().get("message").asText()).containsIgnoringCase("AI service");
	}
}
