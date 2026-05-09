package io.github.huskyagent.infra.web;

import java.util.List;

public record WebSearchResult(
    boolean success,
    List<SearchEntry> entries,
    String error
) {

    public static WebSearchResult success(List<SearchEntry> entries) {
        return new WebSearchResult(true, entries, null);
    }

    public static WebSearchResult failure(String error) {
        return new WebSearchResult(false, List.of(), error);
    }

    public record SearchEntry(
        String title,
        String url,
        String description,
        int position
    ) {}
}