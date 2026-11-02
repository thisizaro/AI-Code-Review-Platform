package com.repomind.api.repo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** What we send to the Python service. {@code truncated} stays on the Java side and is reported in the metrics. */
public record RepoBundle(String url, String owner, String name, String sha, List<RepoFile> files, @JsonIgnore boolean truncated) {}
