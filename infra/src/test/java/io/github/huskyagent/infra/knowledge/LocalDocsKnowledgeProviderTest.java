package io.github.huskyagent.infra.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocsKnowledgeProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void searchesAndFetchesConfiguredLocalDocs() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("runbook.md"), "# Runbook\n\nDeploy Husky with release jars and health checks.");

        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("docs");
        source.setName("Project Docs");
        source.setRoot(docs.toString());
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        List<KnowledgeResult> results = provider.search(KnowledgeQuery.of("release jars", 5, null), Set.of(LocalDocsKnowledgeProvider.NAME));

        assertEquals(1, results.size());
        assertEquals("Project Docs", results.get(0).source());
        assertTrue(results.get(0).snippet().contains("release jars"));

        KnowledgeDocument document = provider.fetch(results.get(0).id(), Set.of(LocalDocsKnowledgeProvider.NAME)).orElseThrow();
        assertTrue(document.content().contains("Deploy Husky"));
    }

    @Test
    void preventsFetchingOutsideConfiguredRoot() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("docs");
        source.setRoot(docs.toString());
        source.setExtensions(Set.of(".md"));
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        assertTrue(provider.fetch("local-docs:docs:..%2Fsecret.md", Set.of(LocalDocsKnowledgeProvider.NAME)).isEmpty());
    }

    @Test
    void sourceIsolationBlocksDisallowedSourceInSearch() throws Exception {
        Path publicDocs = tempDir.resolve("public");
        Path internalDocs = tempDir.resolve("internal");
        Files.createDirectories(publicDocs);
        Files.createDirectories(internalDocs);
        Files.writeString(publicDocs.resolve("faq.md"), "Public FAQ about office hours.");
        Files.writeString(internalDocs.resolve("salary.md"), "Internal salary policy and compensation details.");

        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource publicSource = new KnowledgeConfig.LocalSource();
        publicSource.setId("public");
        publicSource.setName("Public Docs");
        publicSource.setRoot(publicDocs.toString());
        KnowledgeConfig.LocalSource internalSource = new KnowledgeConfig.LocalSource();
        internalSource.setId("internal");
        internalSource.setName("Internal Docs");
        internalSource.setRoot(internalDocs.toString());
        config.setLocalSources(List.of(publicSource, internalSource));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        // Only allow public source — internal should be invisible
        List<KnowledgeResult> results = provider.search(KnowledgeQuery.of("salary policy", 10, null), Set.of("public"));
        assertTrue(results.stream().noneMatch(r -> r.source().equals("Internal Docs")),
                "Internal docs should not appear when only public is allowed");

        // Allow both — both should appear
        List<KnowledgeResult> allResults = provider.search(KnowledgeQuery.of("salary policy", 10, null), Set.of("public", "internal"));
        assertTrue(allResults.stream().anyMatch(r -> r.source().equals("Internal Docs")));
    }

    @Test
    void sourceIsolationBlocksDisallowedSourceInFetch() throws Exception {
        Path publicDocs = tempDir.resolve("public");
        Files.createDirectories(publicDocs);
        Files.writeString(publicDocs.resolve("faq.md"), "Public FAQ content.");

        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("public");
        source.setRoot(publicDocs.toString());
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        // Fetch with public allowed should succeed
        List<KnowledgeResult> results = provider.search(KnowledgeQuery.of("FAQ", 5, null), Set.of("public"));
        assertFalse(results.isEmpty());
        KnowledgeDocument doc = provider.fetch(results.get(0).id(), Set.of("public")).orElseThrow();
        assertTrue(doc.content().contains("Public FAQ"));

        // Fetch with only a different source allowed should fail
        assertTrue(provider.fetch(results.get(0).id(), Set.of("other-source")).isEmpty());
    }

    @Test
    void symlinkEscapeIsBlocked() throws Exception {
        Path docs = tempDir.resolve("docs");
        Path secret = tempDir.resolve("secret");
        Files.createDirectories(docs);
        Files.createDirectories(secret);
        Files.writeString(secret.resolve("secret.md"), "Top secret content.");
        // Create a symlink inside docs pointing to the secret file
        Files.createSymbolicLink(docs.resolve("link-to-secret.md"), secret.resolve("secret.md"));

        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("docs");
        source.setRoot(docs.toString());
        source.setExtensions(Set.of(".md"));
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        // Search should not return symlinked files that resolve outside root
        List<KnowledgeResult> results = provider.search(KnowledgeQuery.of("top secret", 5, null), Set.of(LocalDocsKnowledgeProvider.NAME));
        assertTrue(results.stream().noneMatch(r -> r.snippet().contains("Top secret")),
                "Symlink pointing outside root should be excluded from search results");
    }

    @Test
    void validateSourceIdsAcceptsProviderNameAndSourceIds() {
        Path docs = tempDir.resolve("docs");
        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("docs");
        source.setRoot(docs.toString());
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);
        KnowledgeManager manager = new KnowledgeManager(List.of(provider), config);

        // Both provider name and source id should be valid
        manager.validateSourceIds(Set.of("local-docs"));
        manager.validateSourceIds(Set.of("docs"));
        manager.validateSourceIds(Set.of("local-docs", "docs"));

        assertThrows(IllegalArgumentException.class,
                () -> manager.validateSourceIds(Set.of("nonexistent")));
    }

    @Test
    void emptyAllowedSourcesMeansAllSources() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("guide.md"), "Deployment guide content.");

        KnowledgeConfig config = new KnowledgeConfig();
        KnowledgeConfig.LocalSource source = new KnowledgeConfig.LocalSource();
        source.setId("docs");
        source.setRoot(docs.toString());
        config.setLocalSources(List.of(source));

        LocalDocsKnowledgeProvider provider = new LocalDocsKnowledgeProvider(config);

        // Empty allowed = all sources visible
        List<KnowledgeResult> results = provider.search(KnowledgeQuery.of("deployment", 5, null), Set.of());
        assertEquals(1, results.size());
    }
}