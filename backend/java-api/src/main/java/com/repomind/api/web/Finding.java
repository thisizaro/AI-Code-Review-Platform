package com.repomind.api.web;

/** One code-quality or security finding. Same shape the frontend renders and the Python service returns. */
public record Finding(String severity, String title, String file, Integer line, String detail) {}
