package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.config.HuskyDataPaths;
import io.github.huskyagent.infra.tool.Toolset;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class SkillLoader {

    @Value("${skill.dir:.husky/skills}")
    private String skillDir;

    @Value("${skill.managed-dirs:}")
    private String managedDirs;

    @Value("${skill.builtin-dir:builtin-skills}")
    private String builtinDir;

    @Value("${skill.disabled:}")
    private List<String> disabledSkills;

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();
    private final SkillPreprocessor preprocessor = new SkillPreprocessor();
    private final SkillManager skillManager;
    private final HuskyDataPaths dataPaths;

    private final Map<String, Path> skillDirMap = new HashMap<>();

    private static final String ENTRY_FILE = "SKILL.md";
    private static final String BUILTIN_RESOURCE_ROOT = "builtin-skills";
    private static final Set<String> LINKED_DIRS = Set.of("references", "templates", "scripts", "assets");
    private static final Pattern SAFE_SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public SkillLoader(SkillManager skillManager, HuskyDataPaths dataPaths) {
        this.skillManager = skillManager;
        this.dataPaths = dataPaths;
    }

    @PostConstruct
    public void load() {
        skillDirMap.clear();
        List<Skill> skills = new ArrayList<>();
        Path userHome = Path.of(System.getProperty("user.home"));
        Path projectDir = Path.of(System.getProperty("user.dir"));

        scanSkillDir(resolveBuiltinSkillDir(),                  "builtin",        skills);
        scanSkillDir(userHome.resolve(".claude").resolve("skills"),   "global-claude",  skills);
        scanSkillDir(userHome.resolve(".husky").resolve("skills"),    "global-husky",   skills);
        scanSkillDir(projectDir.resolve(".claude").resolve("skills"), "project-claude", skills);
        for (Path managedRoot : scanSkillRoots()) {
            scanSkillDir(managedRoot, "managed-husky", skills);
        }

        skillManager.setSkills(skills);
        log.info("Loaded {} skills (builtin + global + project)", skills.size());
    }

    private void scanSkillDir(Path dir, String source, List<Skill> skills) {
        if (!Files.exists(dir)) {
            log.info("Skill {} directory not found: {} — skipping", source, dir);
            return;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                   .sorted()
                   .forEach(skillPath -> {
                       Path skillMd = skillPath.resolve(ENTRY_FILE);
                       if (Files.exists(skillMd)) {
                           loadSingleSkill(skillPath, skillMd, source, skills);
                       } else {
                           try (Stream<Path> subEntries = Files.list(skillPath)) {
                               subEntries.filter(Files::isDirectory)
                                         .sorted()
                                         .forEach(subPath -> {
                                             Path subMd = subPath.resolve(ENTRY_FILE);
                                             if (Files.exists(subMd)) {
                                                 loadSingleSkill(subPath, subMd, source, skills);
                                             }
                                         });
                           } catch (IOException e) {
                               log.warn("Failed to scan category dir: {}", skillPath, e);
                           }
                       }
                   });
        } catch (IOException e) {
            log.error("Failed to list {} skill directory: {}", source, dir, e);
        }
    }

    private void loadSingleSkill(Path skillPath, Path skillMd, String source, List<Skill> skills) {
        try {
            String raw = Files.readString(skillMd);
            Skill skill = parser.parse(skillPath.getFileName().toString(), raw);
            Map<String, List<String>> linkedFiles = scanLinkedFiles(skillPath);
            String preprocessed = preprocessor.preprocess(skill.content(), skillPath);
            Skill fullSkill = Skill.ofDirectory(
                    skill.name(), skill.description(),
                    skill.requiresToolsets(), skill.platforms(),
                    preprocessed, skillPath, linkedFiles);
            if (disabledSkills != null && disabledSkills.contains(fullSkill.name())) {
                log.info("Skipping disabled skill: {}", fullSkill.name());
                return;
            }
            if (skills.stream().anyMatch(s -> s.name().equals(fullSkill.name()))) {
                log.info("[{}] skill '{}' overrides previously loaded skill", source, fullSkill.name());
                skills.removeIf(s -> s.name().equals(fullSkill.name()));
                skillDirMap.remove(fullSkill.name());
            }
            skills.add(fullSkill);
            skillDirMap.put(fullSkill.name(), skillPath);
            log.debug("Loaded {} skill: {} from {} (linked files: {})",
                    source, fullSkill.name(), skillPath.getFileName(), linkedFiles.size());
        } catch (IOException e) {
            log.error("Failed to read skill: {}", skillMd, e);
        } catch (IllegalArgumentException e) {
            log.error("Invalid skill {}: {}", skillPath.getFileName(), e.getMessage());
        }
    }

    private Map<String, List<String>> scanLinkedFiles(Path skillPath) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Path skillRoot = realPathOrNormalized(skillPath);
        for (String subDir : LINKED_DIRS) {
            Path subPath = skillPath.resolve(subDir);
            if (!Files.exists(subPath) || !Files.isDirectory(subPath)) continue;

            List<String> files = new ArrayList<>();
            try (Stream<Path> fileStream = Files.list(subPath)) {
                fileStream.filter(f -> isSafeLinkedFile(skillRoot, f))
                          .sorted()
                          .forEach(f -> files.add(subDir + "/" + f.getFileName().toString()));
            } catch (IOException e) {
                log.warn("Failed to list {}: {}", subPath, e.getMessage());
            }
            if (!files.isEmpty()) result.put(subDir, files);
        }
        return result;
    }

    public Path getSkillDir(String skillName) {
        return skillDirMap.get(skillName);
    }

    public String loadLinkedFile(String skillName, String filePath) {
        Path dir = skillDirMap.get(skillName);
        if (dir == null) return null;

        Path target = resolveLinkedFile(dir, filePath);
        if (target == null) {
            log.warn("Rejected linked file path: {} in skill {}", filePath, skillName);
            return null;
        }

        try {
            return Files.readString(target);
        } catch (IOException e) {
            log.error("Failed to read linked file: {}", target, e);
            return null;
        }
    }

    /**
     * Prefers an on-disk built-in skill directory, but falls back to extracting
     * bundled classpath resources when the packaged runtime has no unpacked copy.
     */
    private Path resolveBuiltinSkillDir() {
        Path dir = resolveSkillDir(builtinDir);
        if (containsSkillEntry(dir)) {
            return dir;
        }
        Path extracted = extractBuiltinSkillsFromClasspath();
        return extracted != null ? extracted : dir;
    }

    private boolean containsSkillEntry(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .anyMatch(this::containsSkillEntryFile);
        } catch (IOException e) {
            log.warn("Failed to inspect built-in skill directory: {}", dir, e);
            return false;
        }
    }

    private boolean containsSkillEntryFile(Path path) {
        if (Files.exists(path.resolve(ENTRY_FILE))) {
            return true;
        }
        try (Stream<Path> subEntries = Files.list(path)) {
            return subEntries.filter(Files::isDirectory)
                    .anyMatch(subPath -> Files.exists(subPath.resolve(ENTRY_FILE)));
        } catch (IOException e) {
            log.warn("Failed to inspect built-in skill subdirectory: {}", path, e);
            return false;
        }
    }

    /**
     * Copies packaged built-in skills into the managed data directory while
     * rejecting absolute and path-traversal resource paths.
     */
    private Path extractBuiltinSkillsFromClasspath() {
        Path targetRoot = dataPaths.rootDirectory().resolve("builtin-skills").toAbsolutePath().normalize();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + BUILTIN_RESOURCE_ROOT + "/**/*");
            boolean extractedAny = false;
            for (Resource resource : resources) {
                if (!resource.isReadable()) continue;
                String path = resourcePath(resource);
                if (path == null || path.isBlank() || path.endsWith("/")) continue;
                Path relative = Path.of(path).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) continue;
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) continue;
                Files.createDirectories(target.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                extractedAny = true;
            }
            return extractedAny ? targetRoot : null;
        } catch (IOException e) {
            log.warn("Failed to extract classpath built-in skills: {}", e.getMessage());
            return null;
        }
    }

    private String resourcePath(Resource resource) throws IOException {
        String url = resource.getURL().toString();
        String marker = BUILTIN_RESOURCE_ROOT + "/";
        int index = url.indexOf(marker);
        if (index < 0) return null;
        String encoded = url.substring(index + marker.length());
        int separator = encoded.indexOf("!");
        if (separator >= 0) {
            encoded = encoded.substring(0, separator);
        }
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private Path resolveSkillDir(String dirName) {
        if (dirName == null || dirName.isBlank() || ".husky/skills".equals(dirName)) {
            return dataPaths.skillsDirectory();
        }
        Path dir = Path.of(dirName);
        if (dir.isAbsolute()) return dir;
        return Path.of(System.getProperty("user.dir")).resolve(dir);
    }

    void setSkillDirForTesting(String skillDir) {
        this.skillDir = skillDir;
    }

    void setManagedDirsForTesting(String managedDirs) {
        this.managedDirs = managedDirs;
    }

    void setBuiltinDirForTesting(String builtinDir) {
        this.builtinDir = builtinDir;
    }

    public Set<Path> getWatchedRoots() {
        Path userHome = Path.of(System.getProperty("user.home"));
        Path projectDir = Path.of(System.getProperty("user.dir"));
        return Stream.concat(
                        Stream.of(
                                resolveSkillDir(builtinDir),
                                userHome.resolve(".claude").resolve("skills"),
                                userHome.resolve(".husky").resolve("skills"),
                                projectDir.resolve(".claude").resolve("skills")
                        ),
                        scanSkillRoots().stream()
                )
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }


    public String createSkill(String name, String description, String content) {
        if (!isSafeSkillName(name)) {
            log.error("Invalid skill name: {}", name);
            return null;
        }
        Path dir = managedSkillRoot();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create skill root dir: {}", dir, e);
            return null;
        }

        Path skillDir = resolveManagedSkillDir(name);
        if (skillDir == null) return null;
        if (Files.exists(skillDir)) {
            log.error("Skill directory already exists: {}", skillDir);
            return null;
        }

        try {
            Files.createDirectories(skillDir);
            String skillMdContent = buildSkillMd(name, description, content);
            Files.writeString(skillDir.resolve(ENTRY_FILE), skillMdContent);
            log.info("Created skill directory: {}", skillDir);
            reload();
            return "Created skill '" + name + "' at " + skillDir;
        } catch (IOException e) {
            log.error("Failed to create skill: {}", name, e);
            return null;
        }
    }

    public String installSkillFromRaw(String slug, String rawSkillMd) {
        Path dir = resolveSkillDir(skillDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create skill root dir: {}", dir, e);
            return null;
        }

        String skillName;
        try {
            Skill parsed = parser.parse(slug, rawSkillMd);
            skillName = parsed.name();
        } catch (Exception e) {
            log.warn("Failed to parse frontmatter for {}, using slug as directory name", slug);
            skillName = slug;
        }

        if (!isSafeSkillName(skillName)) {
            log.error("Invalid skill name from SkillHub: {}", skillName);
            return null;
        }

        if (skillManager.getSkill(skillName) != null) {
            log.error("Skill '{}' already exists locally", skillName);
            return null;
        }

        Path skillDir = resolveManagedSkillDir(skillName);
        if (skillDir == null) return null;
        if (Files.exists(skillDir)) {
            log.error("Skill directory already exists: {}", skillDir);
            return null;
        }

        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve(ENTRY_FILE), rawSkillMd);
            log.info("Installed community skill '{}' from SkillHub slug '{}'", skillName, slug);
            reload();
            return "Installed skill '" + skillName + "' at " + skillDir;
        } catch (IOException e) {
            log.error("Failed to install skill: {}", slug, e);
            return null;
        }
    }

    public String patchSkill(String name, String newContent) {
        Path skillDir = resolveMutableExistingSkillDir(name);
        if (skillDir == null) {
            log.error("Skill directory not found for: {}", name);
            return null;
        }

        Path skillMd = skillDir.resolve(ENTRY_FILE);
        try {
            String raw = Files.readString(skillMd);
            Skill existing = parser.parse(name, raw);
            String patched = buildSkillMd(existing.name(), existing.description(), newContent);
            Files.writeString(skillMd, patched);
            log.info("Patched skill: {}", name);
            reload();
            return "Patched skill '" + name + "'";
        } catch (IOException e) {
            log.error("Failed to patch skill: {}", name, e);
            return null;
        }
    }

    public String deleteSkill(String name) {
        Path skillDir = resolveMutableExistingSkillDir(name);
        if (skillDir == null) {
            log.error("Skill directory not found for: {}", name);
            return null;
        }

        try {
            deleteDirectoryRecursive(skillDir);
            log.info("Deleted skill directory: {}", skillDir);
            reload();
            return "Deleted skill '" + name + "'";
        } catch (IOException e) {
            log.error("Failed to delete skill: {}", name, e);
            return null;
        }
    }

    private String buildSkillMd(String name, String description, String content) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + content;
    }

    private void deleteDirectoryRecursive(Path dir) throws IOException {
        Path root = existingRealPath(managedSkillRoot());
        Path target = existingRealPath(dir);
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Refusing to delete directory outside managed skill root: " + dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(this::deletePath);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void deletePath(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete: " + path, e);
        }
    }

    private boolean isSafeSkillName(String name) {
        return name != null && SAFE_SKILL_NAME.matcher(name).matches() && !name.contains("..");
    }

    private Path managedSkillRoot() {
        return resolveSkillDir(skillDir).toAbsolutePath().normalize();
    }

    public List<Path> managedSkillRoots() {
        return configuredManagedDirValues().stream()
                .map(this::resolveSkillDir)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    private List<Path> scanSkillRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(resolveSkillDir(skillDir).toAbsolutePath().normalize());
        roots.addAll(managedSkillRoots());
        return List.copyOf(roots);
    }

    private List<String> configuredManagedDirValues() {
        List<String> configured = managedDirs == null ? List.of() : Arrays.stream(managedDirs.split(","))
                .map(String::trim)
                .filter(dir -> !dir.isBlank())
                .toList();
        if (configured.isEmpty()) {
            String fallback = skillDir == null || skillDir.isBlank() ? ".husky/skills" : skillDir;
            return List.of(fallback);
        }
        return configured;
    }

    public String managedSkillRootsDescription() {
        return managedSkillRoots().stream()
                .map(Path::toString)
                .collect(Collectors.joining(", "));
    }

    private Path resolveManagedSkillDir(String name) {
        if (!isSafeSkillName(name)) {
            log.error("Invalid skill name: {}", name);
            return null;
        }
        Path root = managedSkillRoot();
        Path resolved = root.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            log.error("Skill path escapes managed root: {}", name);
            return null;
        }
        return resolved;
    }

    private Path resolveMutableExistingSkillDir(String name) {
        if (!isSafeSkillName(name)) {
            log.error("Invalid skill name: {}", name);
            return null;
        }
        Path loaded = skillDirMap.get(name);
        if (loaded == null) return null;
        Path target = existingRealPath(loaded);
        for (Path rootPath : managedSkillRoots()) {
            Path root = existingRealPath(rootPath);
            if (target.startsWith(root) && !target.equals(root)) {
                return target;
            }
        }
        log.warn("Skill is not mutable from configured managed skill dirs: name={}, dir={}, managedDirs={}",
                name, loaded, managedSkillRootsDescription());
        return null;
    }

    /**
     * Restricts linked files to whitelisted subdirectories under the skill root
     * and rejects traversal through symlinks or relative path escapes.
     */
    private Path resolveLinkedFile(Path skillPath, String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        Path relative = Path.of(filePath);
        if (relative.isAbsolute()) return null;
        for (Path segment : relative) {
            if ("..".equals(segment.toString()) || segment.toString().isBlank()) return null;
        }
        if (relative.getNameCount() < 2 || !LINKED_DIRS.contains(relative.getName(0).toString())) return null;

        Path skillRoot = existingRealPath(skillPath);
        Path target = skillPath.resolve(relative).toAbsolutePath().normalize();
        if (!Files.exists(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        Path realTarget = existingRealPath(target);
        if (!realTarget.startsWith(skillRoot)) return null;
        return realTarget;
    }

    private boolean isSafeLinkedFile(Path skillRoot, Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        Path realFile = existingRealPath(file);
        return realFile.startsWith(skillRoot);
    }

    private Path realPathOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private Path existingRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    public void reload() {
        load();
    }
}