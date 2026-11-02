package com.repomind.api.repo;

import java.util.List;

/** Files kept from a repository archive. {@code truncated} is true when a size or count limit dropped something. */
public record ExtractedFiles(List<RepoFile> files, boolean truncated) {}
