package com.repomind.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("repomind")
public record RepomindProperties(String corsOrigin, Github github, Python python, Limits limits, Cache cache) {

	public record Github(String baseUrl, String token, int timeoutSeconds, long maxZipBytes) {}

	public record Python(String baseUrl, int timeoutSeconds) {}

	public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes) {}

	public record Cache(int ttlMinutes) {}
}
