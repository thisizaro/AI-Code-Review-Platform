package com.repomind.api.repo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.repomind.api.RepomindProperties;
import com.repomind.api.github.GitHubClient;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Resolves a repository URL to a filtered file bundle, cached by owner/repo@sha so repeat requests skip the download. */
@Service
public class RepoService {

	private final GitHubClient github;
	private final ZipBundleBuilder builder;
	private final Cache<String, RepoBundle> cache;

	public RepoService(GitHubClient github, RepomindProperties properties) {
		this.github = github;
		this.builder = new ZipBundleBuilder(properties.limits());
		this.cache = Caffeine.newBuilder().maximumSize(20).expireAfterWrite(Duration.ofMinutes(properties.cache().ttlMinutes())).build();
	}

	public RepoBundle load(RepoUrl url) {
		String sha = github.latestSha(url);
		return cache.get(url.owner() + "/" + url.name() + "@" + sha, key -> download(url, sha));
	}

	private RepoBundle download(RepoUrl url, String sha) {
		try (InputStream zip = github.downloadZip(url)) {
			ExtractedFiles extracted = builder.build(zip);
			return new RepoBundle(url.canonical(), url.owner(), url.name(), sha, extracted.files(), extracted.truncated());
		} catch (IOException e) {
			throw new com.repomind.api.ApiException(502, "Could not read the repository archive from GitHub.");
		}
	}
}
