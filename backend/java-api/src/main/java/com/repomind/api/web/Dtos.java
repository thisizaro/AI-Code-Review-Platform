package com.repomind.api.web;

import java.util.List;

/** Request and response shapes of the public API. JSON field names are snake_case (see application.properties). */
public final class Dtos {
	private Dtos() {}

	public record AnalyzeRequest(String repositoryUrl) {}

	public record MatchRequest(String repositoryUrl, String jobDescription) {}

	public record AskRequest(String repositoryUrl, String question) {}

	public record Metrics(int filesAnalyzed, int functionsAnalyzed, int testFiles, boolean truncated) {}

	public record AnalyzeResponse(int qualityScore, List<Finding> issues, List<Finding> securityFindings, Metrics metrics) {}

	public record MatchResponse(int matchScore, List<String> matchedSkills, List<String> missingSkills, String explanation) {}

	public record AskResponse(String answer, List<String> sources) {}

	/** What the Python service returns from /internal/analyze. */
	public record PythonAnalyzeResult(List<Finding> issues, List<Finding> securityFindings, int functionsAnalyzed) {}
}
