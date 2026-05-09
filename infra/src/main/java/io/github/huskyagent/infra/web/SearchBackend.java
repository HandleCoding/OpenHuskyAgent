package io.github.huskyagent.infra.web;

public interface SearchBackend {

    WebSearchResult search(String query, int limit);
}