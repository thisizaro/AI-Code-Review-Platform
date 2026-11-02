package com.repomind.api.web;

import com.repomind.api.web.Dtos.AnalyzeRequest;
import com.repomind.api.web.Dtos.AnalyzeResponse;
import com.repomind.api.web.Dtos.AskRequest;
import com.repomind.api.web.Dtos.AskResponse;
import com.repomind.api.web.Dtos.MatchRequest;
import com.repomind.api.web.Dtos.MatchResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

	private final ApiService service;

	public ApiController(ApiService service) {
		this.service = service;
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}

	@PostMapping("/repositories/analyze")
	public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
		return service.analyze(request.repositoryUrl());
	}

	@PostMapping("/matching/analyze")
	public MatchResponse match(@RequestBody MatchRequest request) {
		return service.match(request.repositoryUrl(), request.jobDescription());
	}

	@PostMapping("/learning/ask")
	public AskResponse ask(@RequestBody AskRequest request) {
		return service.ask(request.repositoryUrl(), request.question());
	}
}
