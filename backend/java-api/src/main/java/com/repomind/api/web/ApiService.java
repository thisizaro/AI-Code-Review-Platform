package com.repomind.api.web;

import com.repomind.api.ApiException;
import com.repomind.api.ai.PythonClient;
import com.repomind.api.repo.RepoBundle;
import com.repomind.api.repo.RepoFile;
import com.repomind.api.repo.RepoService;
import com.repomind.api.repo.RepoUrl;
import com.repomind.api.score.ScoreCalculator;
import com.repomind.api.web.Dtos.AnalyzeResponse;
import com.repomind.api.web.Dtos.AskResponse;
import com.repomind.api.web.Dtos.MatchResponse;
import com.repomind.api.web.Dtos.Metrics;
import com.repomind.api.web.Dtos.PythonAnalyzeResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ApiService {

	static final int MAX_JOB_DESCRIPTION = 10_000;
	static final int MAX_QUESTION = 1_000;

	private final RepoService repos;
	private final PythonClient python;

	public ApiService(RepoService repos, PythonClient python) {
		this.repos = repos;
		this.python = python;
	}

	public AnalyzeResponse analyze(String repositoryUrl) {
		RepoBundle bundle = repos.load(RepoUrl.parse(repositoryUrl));
		PythonAnalyzeResult found = python.post("/internal/analyze", Map.of("repo", bundle), PythonAnalyzeResult.class);
		List<Finding> issues = orEmpty(found.issues());
		List<Finding> security = orEmpty(found.securityFindings());
		int files = bundle.files().size();
		int tests = ScoreCalculator.countTestFiles(bundle.files().stream().map(RepoFile::path).toList());
		int score = ScoreCalculator.qualityScore(issues, security, tests, files);
		return new AnalyzeResponse(score, issues, security, new Metrics(files, found.functionsAnalyzed(), tests, bundle.truncated()));
	}

	public MatchResponse match(String repositoryUrl, String jobDescription) {
		RepoUrl url = RepoUrl.parse(repositoryUrl);
		require(jobDescription, "Provide a job description.", MAX_JOB_DESCRIPTION, "Job description is too long (max " + MAX_JOB_DESCRIPTION + " characters).");
		RepoBundle bundle = repos.load(url);
		return python.post("/internal/match", Map.of("repo", bundle, "job_description", jobDescription.trim()), MatchResponse.class);
	}

	public AskResponse ask(String repositoryUrl, String question) {
		RepoUrl url = RepoUrl.parse(repositoryUrl);
		require(question, "Enter a question about the repository.", MAX_QUESTION, "Question is too long (max " + MAX_QUESTION + " characters).");
		RepoBundle bundle = repos.load(url);
		return python.post("/internal/ask", Map.of("repo", bundle, "question", question.trim()), AskResponse.class);
	}

	private static void require(String value, String blankMessage, int max, String tooLongMessage) {
		if (value == null || value.isBlank()) {
			throw new ApiException(400, blankMessage);
		}
		if (value.length() > max) {
			throw new ApiException(400, tooLongMessage);
		}
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}
}
