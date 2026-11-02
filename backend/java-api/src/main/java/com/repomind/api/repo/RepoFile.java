package com.repomind.api.repo;

public record RepoFile(String path, String language, long size, String content) {}
