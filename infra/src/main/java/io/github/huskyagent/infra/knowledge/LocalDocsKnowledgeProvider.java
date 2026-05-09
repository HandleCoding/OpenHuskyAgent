package io.github.huskyagent.infra.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Component
public class LocalDocsKnowledgeProvider implements KnowledgeProvider {
    public static final String NAME = "local-docs";

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "target", ".idea", "__pycache__", ".gradle", "build", "dist", ".cache", "venv", ".tox", ".mvn"
    );

    private static final long MAX_FILE_SIZE_BYTES = 512 * 1024;

    private final KnowledgeConfig config;

    public LocalDocsKnowledgeProvider(KnowledgeConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Searches configured local documentation directories without indexing or embeddings.";
    }

    @Override
    public Set<String> getSourceIds() {
        return config.getLocalSources().stream()
                .filter(KnowledgeConfig.LocalSource::isEnabled)
                .map(KnowledgeConfig.LocalSource::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean supportsSource(String sourceId) {
        return KnowledgeProvider.super.supportsSource(sourceId) || getSourceIds().contains(sourceId);
    }

    @Override
    public boolean isAvailable() {
        return config.isEnabled() && config.getLocalSources().stream().anyMatch(this::sourceAvailable);
    }

    @Override
    public List<KnowledgeResult> search(KnowledgeQuery query, Set<String> allowedSourceIds) {
        if (query.query() == null || query.query().isBlank()) {
            return List.of();
        }
        Set<String> effectiveAllowed = allowedSourceIds == null || allowedSourceIds.isEmpty()
                ? Set.of(NAME) : allowedSourceIds;
        List<String> terms = terms(query.query());
        List<KnowledgeResult> results = new ArrayList<>();
        for (KnowledgeConfig.LocalSource source : config.getLocalSources()) {
            if (!sourceAvailable(source) || !sourceAllowed(source, effectiveAllowed)) {
                continue;
            }
            if (query.source() != null && !query.source().isBlank()
                    && !getName().equals(query.source()) && !query.source().equals(source.getId())) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root(source), config.getMaxWalkDepth())) {
                Path realRoot = realRoot(source);
                files.filter(Files::isRegularFile)
                        .filter(path -> isAllowedExtension(path, source.getExtensions()))
                        .filter(path -> !ignored(path))
                        .filter(path -> fileSizeWithinLimit(path))
                        .filter(path -> isUnderRealRoot(path, realRoot))
                        .forEach(path -> addMatch(results, source, path, terms));
            } catch (IOException e) {
                log.warn("Failed to search knowledge source {}: {}", source.getId(), e.getMessage());
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(KnowledgeResult::score).reversed())
                .limit(query.topK() > 0 ? query.topK() : config.getDefaultTopK())
                .toList();
    }

    @Override
    public Optional<KnowledgeDocument> fetch(String id, Set<String> allowedSourceIds) {
        DecodedId decoded = decode(id);
        if (decoded == null) {
            return Optional.empty();
        }
        Set<String> effectiveAllowed = allowedSourceIds == null || allowedSourceIds.isEmpty()
                ? Set.of(NAME) : allowedSourceIds;
        if (!sourceAllowedById(decoded.sourceId(), effectiveAllowed)) {
            return Optional.empty();
        }
        KnowledgeConfig.LocalSource source = findSource(decoded.sourceId()).orElse(null);
        if (!sourceAvailable(source)) {
            return Optional.empty();
        }
        Path root = realRoot(source);
        Path file = root.resolve(decoded.relativePath()).normalize();
        // Resolve symlinks, then verify real path is still under the real root
        try {
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(root) || !Files.isRegularFile(realFile)) {
                return Optional.empty();
            }
            if (!isAllowedExtension(realFile, source.getExtensions())) {
                return Optional.empty();
            }
            if (Files.size(realFile) > MAX_FILE_SIZE_BYTES) {
                return Optional.empty();
            }
            String content = Files.readString(realFile, StandardCharsets.UTF_8);
            if (content.length() > config.getMaxDocumentChars()) {
                content = content.substring(0, config.getMaxDocumentChars())
                        + "... [truncated, total " + Files.size(realFile) + " bytes]";
            }
            return Optional.of(new KnowledgeDocument(
                    id,
                    title(realFile),
                    content,
                    sourceName(source),
                    getName(),
                    lastModified(realFile),
                    Map.of("path", realFile.toString(), "sourceId", source.getId())
            ));
        } catch (IOException e) {
            log.warn("Failed to fetch knowledge document {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean sourceAllowed(KnowledgeConfig.LocalSource source, Set<String> allowed) {
        return allowed.contains(getName()) || allowed.contains(source.getId());
    }

    private boolean sourceAllowedById(String sourceId, Set<String> allowed) {
        return allowed.contains(getName()) || allowed.contains(sourceId);
    }

    private void addMatch(List<KnowledgeResult> results, KnowledgeConfig.LocalSource source, Path file, List<String> terms) {
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE_BYTES) {
                return;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            double score = score(content, file, terms);
            if (score <= 0) {
                return;
            }
            results.add(new KnowledgeResult(
                    encode(source, file),
                    title(file),
                    snippet(content, terms),
                    sourceName(source),
                    getName(),
                    score,
                    lastModified(file),
                    Map.of("path", file.toString(), "sourceId", source.getId())
            ));
        } catch (IOException e) {
            log.debug("Skipping unreadable knowledge file {}", file);
        }
    }

    private boolean fileSizeWithinLimit(Path path) {
        try {
            return Files.size(path) <= MAX_FILE_SIZE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private double score(String content, Path file, List<String> terms) {
        String text = content.toLowerCase(Locale.ROOT);
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            int occurrences = countOccurrences(text, term);
            if (occurrences > 0) {
                score += occurrences;
            }
            if (filename.contains(term)) {
                score += 3;
            }
        }
        return score;
    }

    private String snippet(String content, List<String> terms) {
        String lower = content.toLowerCase(Locale.ROOT);
        int first = terms.stream()
                .mapToInt(lower::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(0);
        int max = Math.max(100, config.getMaxSnippetChars());
        int start = Math.max(0, first - max / 3);
        int end = Math.min(content.length(), start + max);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < content.length() ? "..." : "";
        return prefix + content.substring(start, end).replaceAll("\\s+", " ").trim() + suffix;
    }

    private boolean sourceAvailable(KnowledgeConfig.LocalSource source) {
        return source != null && source.isEnabled() && source.getRoot() != null && Files.isDirectory(root(source));
    }

    private boolean isUnderRealRoot(Path file, Path realRoot) {
        try {
            // Resolve the file's real path (following symlinks) and check it starts with realRoot.
            // NOFOLLOW_LINKS only stops at the final component: if file itself is a symlink,
            // its target is NOT resolved. We want to resolve the whole path to detect
            // symlinks at any level, so use the default (follow all symlinks).
            Path realFile = file.toRealPath();
            return realFile.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<KnowledgeConfig.LocalSource> findSource(String sourceId) {
        return config.getLocalSources().stream()
                .filter(source -> source.getId() != null && source.getId().equals(sourceId))
                .findFirst();
    }

    private Path root(KnowledgeConfig.LocalSource source) {
        String raw = source.getRoot();
        if (raw.startsWith("~")) {
            raw = raw.replaceFirst("^~", System.getProperty("user.home"));
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    private Path realRoot(KnowledgeConfig.LocalSource source) {
        try {
            return root(source).toRealPath();
        } catch (IOException e) {
            return root(source);
        }
    }

    private boolean ignored(Path path) {
        for (Path part : path) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedExtension(Path path, Set<String> extensions) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        Set<String> allowed = extensions == null || extensions.isEmpty()
                ? Set.of(".md", ".mdx", ".txt", ".adoc", ".rst")
                : extensions;
        return allowed.stream().anyMatch(ext -> name.endsWith(ext.toLowerCase(Locale.ROOT)));
    }

    private List<String> terms(String query) {
        return Stream.of(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = text.indexOf(term);
        while (index >= 0) {
            count++;
            index = text.indexOf(term, index + term.length());
        }
        return count;
    }

    private String title(Path file) {
        return file.getFileName().toString();
    }

    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private String sourceName(KnowledgeConfig.LocalSource source) {
        return source.getName() != null && !source.getName().isBlank() ? source.getName() : source.getId();
    }

    private String encode(KnowledgeConfig.LocalSource source, Path file) {
        String relative = root(source).relativize(file).toString();
        return NAME + ":" + source.getId() + ":" + URLEncoder.encode(relative, StandardCharsets.UTF_8);
    }

    private DecodedId decode(String id) {
        if (id == null || !id.startsWith(NAME + ":")) {
            return null;
        }
        String[] parts = id.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        return new DecodedId(parts[1], URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
    }

    private record DecodedId(String sourceId, String relativePath) {}
}