package com.repomind.api.github;

import com.repomind.api.ApiException;
import com.repomind.api.RepomindProperties;
import com.repomind.api.repo.RepoUrl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Read-only access to the public GitHub REST API: latest commit sha and a zipball of the default branch. */
@Component
public class GitHubClient {

	private static final int MAX_REDIRECTS = 3;

	private final RepomindProperties.Github config;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10)).build();

	public GitHubClient(RepomindProperties properties) {
		this.config = properties.github();
	}

	public String latestSha(RepoUrl repo) {
		HttpResponse<byte[]> response = get(base(repo) + "/commits/HEAD", true, "application/vnd.github.sha", HttpResponse.BodyHandlers.ofByteArray());
		return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8).trim();
	}

	/** Downloads the default-branch archive. The caller owns (and must close) the stream. */
	public InputStream downloadZip(RepoUrl repo) {
		HttpResponse<InputStream> response = get(base(repo) + "/zipball", true, "application/vnd.github+json", HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			byte[] bytes = body.readNBytes((int) config.maxZipBytes() + 1);
			if (bytes.length > config.maxZipBytes()) {
				throw new ApiException(413, "This repository is too large to analyze (archive over " + config.maxZipBytes() / (1024 * 1024) + " MB).");
			}
			return new ByteArrayInputStream(bytes);
		} catch (IOException e) {
			throw new ApiException(502, "GitHub is unavailable. Please try again.");
		}
	}

	private String base(RepoUrl repo) {
		return config.baseUrl() + "/repos/" + repo.owner() + "/" + repo.name();
	}

	private <T> HttpResponse<T> get(String url, boolean authenticate, String accept, HttpResponse.BodyHandler<T> handler) {
		String current = url;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(current))
					.timeout(Duration.ofSeconds(config.timeoutSeconds()))
					.header("Accept", accept)
					.header("User-Agent", "repomind-api");
			if (authenticate && hop == 0 && config.token() != null && !config.token().isBlank()) {
				request.header("Authorization", "Bearer " + config.token());
			}
			HttpResponse<T> response = send(request.build(), handler);
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				current = redirectTarget(response);
				continue;
			}
			if (status >= 200 && status < 300) {
				return response;
			}
			throw mapError(response);
		}
		throw new ApiException(502, "GitHub redirected too many times.");
	}

	private <T> String redirectTarget(HttpResponse<T> response) {
		String location = response.headers().firstValue("Location").orElseThrow(() -> new ApiException(502, "GitHub returned an invalid redirect."));
		URI target = URI.create(location);
		String host = target.getHost() == null ? "" : target.getHost();
		String baseHost = URI.create(config.baseUrl()).getHost();
		boolean trusted = host.equals(baseHost) || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com");
		if (!trusted) {
			throw new ApiException(502, "GitHub returned an unexpected redirect.");
		}
		return location;
	}

	private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
		try {
			return http.send(request, handler);
		} catch (HttpTimeoutException e) {
			throw new ApiException(504, "GitHub took too long to respond. Please try again.");
		} catch (IOException e) {
			throw new ApiException(502, "Could not reach GitHub. Please try again.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "The request was interrupted.");
		}
	}

	private <T> ApiException mapError(HttpResponse<T> response) {
		int status = response.statusCode();
		boolean exhausted = "0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse(""));
		if (status == 429 || (status == 403 && exhausted)) {
			return new ApiException(429, "GitHub rate limit exceeded. Try again later or set a GITHUB_TOKEN on the server.");
		}
		return switch (status) {
			case 404 -> new ApiException(404, "Repository not found. Check the URL and make sure the repository is public.");
			case 403 -> new ApiException(403, "GitHub denied access to this repository.");
			case 401 -> new ApiException(502, "The server's GitHub token was rejected.");
			default -> new ApiException(502, "GitHub is unavailable (HTTP " + status + "). Please try again.");
		};
	}
}
