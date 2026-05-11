package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.config.HuskyDataPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private SkillManager skillManager;
    private SkillLoader loader;
    private Path managedSkills;
    private Path builtinSkills;

    @BeforeEach
    void setUp() throws IOException {
        skillManager = new SkillManager();
        loader = new SkillLoader(skillManager, new HuskyDataPaths(tempDir.resolve("data").toString()));
        managedSkills = tempDir.resolve("managed-skills");
        builtinSkills = tempDir.resolve("builtin-skills");
        Files.createDirectories(managedSkills);
        Files.createDirectories(builtinSkills);
        loader.setSkillDirForTesting(managedSkills.toString());
        loader.setBuiltinDirForTesting(builtinSkills.toString());
    }

    @Test
    void createSkillRejectsTraversalName() {
        assertNull(loader.createSkill("../evil", "desc", "content"));
        assertFalse(Files.exists(tempDir.resolve("evil")));
    }

    @Test
    void createSkillRejectsPathSeparatorName() {
        assertNull(loader.createSkill("nested/evil", "desc", "content"));
        assertFalse(Files.exists(managedSkills.resolve("nested")));
    }

    @Test
    void installSkillFromRawRejectsTraversalFrontmatterName() {
        String raw = skillMd("../evil", "desc", "content");
        assertNull(loader.installSkillFromRaw("safe-slug", raw));
        assertFalse(Files.exists(tempDir.resolve("evil")));
    }

    @Test
    void installSkillFromRawRejectsTraversalSlugWhenFrontmatterInvalid() {
        assertNull(loader.installSkillFromRaw("../evil", "not frontmatter"));
        assertFalse(Files.exists(tempDir.resolve("evil")));
    }

    @Test
    void loadLinkedFileRejectsTraversalAndUnsupportedPaths() throws IOException {
        createManagedSkill("safe", List.of("references/api.md"));
        loader.load();

        assertNull(loader.loadLinkedFile("safe", "../secret.md"));
        assertNull(loader.loadLinkedFile("safe", tempDir.resolve("secret.md").toString()));
        assertNull(loader.loadLinkedFile("safe", "SKILL.md"));
        assertNull(loader.loadLinkedFile("safe", "references/../../secret.md"));
    }

    @Test
    void loadLinkedFileRejectsSymlinkEscapingSkillDir() throws IOException {
        Path skillDir = createManagedSkill("safe", List.of());
        Files.createDirectories(skillDir.resolve("references"));
        Path secret = tempDir.resolve("secret.md");
        Files.writeString(secret, "secret");
        try {
            Files.createSymbolicLink(skillDir.resolve("references/leak.md"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        loader.load();

        assertNull(loader.loadLinkedFile("safe", "references/leak.md"));
    }

    @Test
    void patchSkillRejectsNonManagedSkill() throws IOException {
        createBuiltinSkill("builtin", "original");
        loader.load();

        assertNull(loader.patchSkill("builtin", "patched"));
        assertEquals("original", skillManager.getSkill("builtin").content());
    }

    @Test
    void deleteSkillRejectsNonManagedSkill() throws IOException {
        Path builtin = createBuiltinSkill("builtin", "original");
        loader.load();

        assertNull(loader.deleteSkill("builtin"));
        assertTrue(Files.exists(builtin.resolve("SKILL.md")));
    }

    @Test
    void createPatchAndDeleteManagedSkill() {
        assertNotNull(loader.createSkill("managed", "desc", "content"));
        assertNotNull(loader.patchSkill("managed", "patched"));
        assertEquals("patched", skillManager.getSkill("managed").content());
        assertNotNull(loader.deleteSkill("managed"));
        assertNull(skillManager.getSkill("managed"));
    }

    @Test
    void patchAllowsConfiguredManagedDirBeyondSkillDir() throws IOException {
        Path extraManaged = tempDir.resolve("extra-managed-skills");
        loader.setManagedDirsForTesting(managedSkills + "," + extraManaged);
        createSkill(extraManaged, "global", "original");
        loader.load();

        assertNotNull(loader.patchSkill("global", "patched"));
        assertEquals("patched", skillManager.getSkill("global").content());
    }

    @Test
    void loadUsesConfiguredSkillRootsOnly() throws IOException {
        Path fixedGlobal = tempDir.resolve("home/.husky/skills");
        Path fixedClaude = tempDir.resolve("home/.claude/skills");
        Path fixedProject = tempDir.resolve("project/.claude/skills");
        String oldUserHome = System.getProperty("user.home");
        String oldUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", tempDir.resolve("home").toString());
            System.setProperty("user.dir", tempDir.resolve("project").toString());
            createSkill(fixedGlobal, "global_husky", "global");
            createSkill(fixedClaude, "global_claude", "global");
            createSkill(fixedProject, "project_claude", "project");
            createManagedSkill("configured", List.of());

            loader.load();

            assertNotNull(skillManager.getSkill("configured"));
            assertNull(skillManager.getSkill("global_husky"));
            assertNull(skillManager.getSkill("global_claude"));
            assertNull(skillManager.getSkill("project_claude"));
        } finally {
            System.setProperty("user.home", oldUserHome);
            System.setProperty("user.dir", oldUserDir);
        }
    }

    @Test
    void managedSkillRootsDefaultToSkillDir() {
        assertEquals(List.of(managedSkills.toAbsolutePath().normalize()), loader.managedSkillRoots());
    }

    @Test
    void loadsBuiltInSkillsFromClasspathWhenFilesystemDirMissing() {
        loader.setBuiltinDirForTesting(tempDir.resolve("missing-builtin-skills").toString());
        loader.load();

        assertNotNull(skillManager.getSkill("plan"));
        assertNotNull(skillManager.getSkill("systematic-debugging"));
        assertNotNull(skillManager.getSkill("web-testing"));
    }

    @Test
    void loadsBuiltInSkillsFromClasspathWhenFilesystemDirIsEmpty() throws IOException {
        Path emptyBuiltin = tempDir.resolve("empty-builtin-skills");
        Files.createDirectories(emptyBuiltin);
        loader.setBuiltinDirForTesting(emptyBuiltin.toString());
        loader.load();

        assertNotNull(skillManager.getSkill("plan"));
        assertNotNull(skillManager.getSkill("systematic-debugging"));
        assertNotNull(skillManager.getSkill("web-testing"));
    }

    private Path createManagedSkill(String name, List<String> linkedFiles) throws IOException {
        Path dir = createSkill(managedSkills, name, "content");
        for (String linkedFile : linkedFiles) {
            Path path = dir.resolve(linkedFile);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "linked content");
        }
        return dir;
    }

    private Path createSkill(Path root, String name, String content) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name, "desc", content));
        return dir;
    }

    private Path createBuiltinSkill(String name, String content) throws IOException {
        Path dir = builtinSkills.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name, "desc", content));
        return dir;
    }

    private String skillMd(String name, String description, String content) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + content;
    }
}
