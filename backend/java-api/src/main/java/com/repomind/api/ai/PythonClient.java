package com.repomind.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.api.ApiException;
import com.repomind.api.RepomindProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Calls the internal Python AI service and translates its failures into our error statuses. */
@Component
public class PythonClient {

	private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

	private final RepomindProperties.Python config;
	private final ObjectMapper mapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public PythonClient(RepomindProperties properties, ObjectMapper mapper) {
		this.config = properties.python();
		this.mapper = mapper;
	}

	public <T> T post(String path, Object body, Class<T> type) {
		HttpResponse<String> response = send(path, body);
		int status = response.statusCode();
		if (status >= 200 && status < 300) {
			try {
				return mapper.readValue(response.body(), type);
			} catch (IOException e) {
				log.warn("python {} returned unreadable JSON", path);
				throw new ApiException(502, "The AI service returned an unreadable response.");
			}
		}
		if (status == 400) {
			throw new ApiException(400, detail(response.body()));
		}
		log.warn("python {} failed with HTTP {}", path, status);
		throw new ApiException(502, "The AI service failed to process the request.");
	}

	private HttpResponse<String> send(String path, Object body) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
					.timeout(Duration.ofSeconds(config.timeoutSeconds()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
					.build();
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new ApiException(504, "The AI service took too long to respond. Please try again.");
		} catch (IOException e) {
			throw new ApiException(502, "The AI service is not reachable. Make sure it is running.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "The request was interrupted.");
		}
	}

	private String detail(String body) {
		try {
			JsonNode detail = mapper.readTree(body).get("detail");
			if (detail != null && detail.isTextual()) {
				return detail.asText();
			}
		} catch (IOException ignored) {
			// fall through to the generic message
		}
		return "The request was rejected.";
	}
}
