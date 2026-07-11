package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.session.SessionScope;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.adapter.ToolRuntimeEnvironment;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.tool.state.ToolStateStore;
import io.github.huskyagent.infra.workspace.LocalWorkspace;
import io.github.huskyagent.infra.workspace.ScopedWorkspace;
import io.github.huskyagent.infra.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir
    Path tempDir;

    private ReadFileTool readFileTool;
    private WriteFileTool writeFileTool;
    private EditFileTool editFileTool;
    private MoveFileTool moveFileTool;
    private SearchFilesTool searchFilesTool;
    private ListFilesTool listFilesTool;
    private ToolStateStore toolStateStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Workspace workspace = new LocalWorkspace();

    private static final String SESSION_ID = "test-session";

    @BeforeEach
    void setUp() {
        toolStateStore = new ToolStateStore();
        ToolLimitsConfig limitsConfig = new ToolLimitsConfig();
        readFileTool = new ReadFileTool(toolStateStore, limitsConfig, workspace);
        writeFileTool = new WriteFileTool(toolStateStore, workspace);
        editFileTool = new EditFileTool(toolStateStore, workspace);
        moveFileTool = new MoveFileTool(workspace);
        searchFilesTool = new SearchFilesTool(workspace);
        listFilesTool = new ListFilesTool(workspace);
    }

    @Test
    void testWriteAndReadFile() throws IOException {
        Map<String, Object> writeArgs = Map.of(
            "path", tempDir.resolve("test.txt").toString(),
            "content", "Hello, World!\nLine 2\nLine 3"
        );

        ToolResult writeResult = writeFileTool.handle(writeArgs);
        assertTrue(writeResult.success());
        assertNotNull(writeResult.content());

        Map<String, Object> readArgs = Map.of(
            "path", tempDir.resolve("test.txt").toString()
        );

        ToolResult readResult = readFileTool.handle(readArgs);
        assertTrue(readResult.success());
        String content = extractContent(readResult);
        assertTrue(content.contains("Hello, World!"));
        assertTrue(content.contains("Line 2"));
    }

    @Test
    void writeFileReturnsUnavailableWhenContextHasNoFilesystem() {
        ToolRuntimeEnvironment environment = new ToolRuntimeEnvironment(
                "ssh",
                false,
                () -> workspace,
                () -> null);
        ToolExecutionContext context = new ToolExecutionContext(
                SESSION_ID,
                SessionScope.builder()
                        .sessionId(SESSION_ID)
                        .backendType("ssh")
                        .filesystemAvailable(false)
                        .build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                environment);

        ToolResult result = writeFileTool.handle(
                Map.of("path", tempDir.resolve("blocked.txt").toString(), "content", "blocked"),
                context);

        assertFalse(result.success());
        assertTrue(result.error().contains("File tools are not available"));
        assertFalse(Files.exists(tempDir.resolve("blocked.txt")));
    }

    @Test
    void readFileMapsDockerRuntimeAbsolutePathToHostWorkspace() throws IOException {
        Files.writeString(tempDir.resolve("mapped.txt"), "from docker workspace");
        ToolExecutionContext context = dockerFileContext();

        ToolResult result = readFileTool.handle(Map.of("path", "/workspace/mapped.txt"), context);

        assertTrue(result.success());
        assertTrue(extractContent(result).contains("from docker workspace"));
    }

    @Test
    void fileToolFailsClosedWhenContextHasNoRuntimeEnvironment() {
        ToolExecutionContext context = new ToolExecutionContext(
                SESSION_ID,
                SessionScope.builder().sessionId(SESSION_ID).backendType("docker").filesystemAvailable(true).build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of());

        ToolResult result = readFileTool.handle(Map.of("path", tempDir.resolve("x.txt").toString()), context);

        assertFalse(result.success());
        assertTrue(result.error().contains("No runtime environment"));
    }

    private ToolExecutionContext dockerFileContext() {
        Workspace scopedWorkspace = new ScopedWorkspace(workspace, tempDir, Path.of("/workspace"));
        ToolRuntimeEnvironment environment = new ToolRuntimeEnvironment(
                "docker",
                true,
                () -> scopedWorkspace,
                () -> null);
        return new ToolExecutionContext(
                SESSION_ID,
                SessionScope.builder()
                        .sessionId(SESSION_ID)
                        .backendType("docker")
                        .filesystemAvailable(true)
                        .workingDirectory(tempDir.toString())
                        .runtimeWorkingDirectory("/workspace")
                        .build(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                environment);
    }

    @Test
    void testReadFileWithOffsetAndLimit() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> args = Map.of(
            "path", file.toString(),
            "offset", 2,
            "limit", 2
        );

        ToolResult result = readFileTool.handle(args);
        assertTrue(result.success());
        String content = extractContent(result);
        assertTrue(content.contains("Line 2"));
        assertTrue(content.contains("Line 3"));
        assertFalse(content.contains("Line 1"));
        assertFalse(content.contains("Line 5"));
    }

    @Test
    void readFileReadsWindowFromLargeFile() throws IOException {
        Path file = tempDir.resolve("large.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            content.append("Line ").append(i).append(" with enough padding to exceed the old full-file limit\n");
        }
        Files.writeString(file, content.toString());

        ToolResult result = readFileTool.handle(Map.of(
                "path", file.toString(),
                "offset", 4000,
                "limit", 2
        ));

        assertTrue(result.success(), result.error());
        String output = extractContent(result);
        assertTrue(output.contains("Line 4000"));
        assertTrue(output.contains("Line 4001"));
        assertFalse(output.contains("Line 1"));
    }

    @Test
    void testReadNonExistentFile() {
        Map<String, Object> args = Map.of(
            "path", tempDir.resolve("nonexistent.txt").toString()
        );

        ToolResult result = readFileTool.handle(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("not found"));
    }

    @Test
    void testWriteFileCreatesParentDirs() throws IOException {
        Map<String, Object> args = Map.of(
            "path", tempDir.resolve("subdir/deep/test.txt").toString(),
            "content", "nested content"
        );

        ToolResult result = writeFileTool.handle(args);
        assertTrue(result.success());
        assertTrue(Files.exists(tempDir.resolve("subdir/deep/test.txt")));
    }

    @Test
    void readEnvFileIsDeniedButExampleIsAllowed() throws IOException {
        Path env = tempDir.resolve(".env");
        Path envExample = tempDir.resolve(".env.example");
        Files.writeString(env, "OPENAI_API_KEY=secret");
        Files.writeString(envExample, "OPENAI_API_KEY=");

        ToolResult denied = readFileTool.handle(Map.of("path", env.toString()));
        assertFalse(denied.success());
        assertTrue(denied.error().contains("environment secrets"));

        ToolResult allowed = readFileTool.handle(Map.of("path", envExample.toString()));
        assertTrue(allowed.success(), allowed.error());
        assertTrue(extractContent(allowed).contains("OPENAI_API_KEY="));
    }

    @Test
    void writeSensitivePathIsDenied() {
        ToolResult result = writeFileTool.handle(Map.of(
                "path", Path.of(System.getProperty("user.home"), ".ssh", "id_rsa").toString(),
                "content", "secret"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("protected") || result.error().contains("credential"));
    }

    @Test
    void readSymlinkToSensitivePathIsDenied() throws IOException {
        Path link = tempDir.resolve("passwd-link");
        Files.createSymbolicLink(link, Path.of("/etc/passwd"));

        ToolResult result = readFileTool.handle(Map.of("path", link.toString()));

        assertFalse(result.success());
        assertTrue(result.error().contains("protected"));
    }

    @Test
    void moveFileFailsWhenDestinationExists() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Path destination = tempDir.resolve("destination.txt");
        Files.writeString(source, "source");
        Files.writeString(destination, "destination");

        ToolResult result = moveFileTool.handle(Map.of(
                "source", source.toString(),
                "destination", destination.toString()
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("already exists"));
        assertEquals("source", Files.readString(source));
        assertEquals("destination", Files.readString(destination));
    }

    @Test
    void testEditFileReplace() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "original content here");
        toolStateStore.markRead(SESSION_ID, file.toString());

        Map<String, Object> args = new java.util.HashMap<>(Map.of(
            "path", file.toString(),
            "old_string", "original",
            "new_string", "modified"
        ));
        args.put(ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID);

        ToolResult result = editFileTool.handle(args);
        assertTrue(result.success());

        String content = Files.readString(file);
        assertEquals("modified content here", content);
    }

    @Test
    void testEditFileNotFound() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "some content");
        toolStateStore.markRead(SESSION_ID, file.toString());

        Map<String, Object> args = new java.util.HashMap<>(Map.of(
            "path", file.toString(),
            "old_string", "not present",
            "new_string", "replacement"
        ));
        args.put(ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID);

        ToolResult result = editFileTool.handle(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("not found"));
    }

    @Test
    void testEditFileMultipleMatches() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "word word word");
        toolStateStore.markRead(SESSION_ID, file.toString());

        Map<String, Object> args = new java.util.HashMap<>(Map.of(
            "path", file.toString(),
            "old_string", "word",
            "new_string", "replace"
        ));
        args.put(ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID);

        ToolResult result = editFileTool.handle(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("3 matches") || result.error().contains("replace_all"));
    }

    @Test
    void testEditFileReplaceAll() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "word word word");
        toolStateStore.markRead(SESSION_ID, file.toString());

        Map<String, Object> args = new java.util.HashMap<>(Map.of(
            "path", file.toString(),
            "old_string", "word",
            "new_string", "replace",
            "replace_all", true
        ));
        args.put(ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID);

        ToolResult result = editFileTool.handle(args);
        assertTrue(result.success());

        String content = Files.readString(file);
        assertEquals("replace replace replace", content);
    }

    @Test
    void readTildePathThenEditAbsolutePathUsesSameFileState() throws IOException {
        String oldHome = System.getProperty("user.home");
        Path fakeHome = tempDir.resolve("home");
        Files.createDirectories(fakeHome);
        Path file = fakeHome.resolve("home-edit.txt");
        Files.writeString(file, "hello from home");
        try {
            System.setProperty("user.home", fakeHome.toString());
            ToolResult read = readFileTool.handle(new java.util.HashMap<>(Map.of(
                    "path", "~/home-edit.txt",
                    ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
            )));
            assertTrue(read.success(), read.error());

            ToolResult edit = editFileTool.handle(new java.util.HashMap<>(Map.of(
                    "path", file.toString(),
                    "old_string", "hello",
                    "new_string", "hi",
                    ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
            )));
            assertTrue(edit.success(), edit.error());
            assertEquals("hi from home", Files.readString(file));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void staleFileWriteReturnsWarning() throws Exception {
        Path file = tempDir.resolve("stale.txt");
        Files.writeString(file, "original");
        ToolResult read = readFileTool.handle(new java.util.HashMap<>(Map.of(
                "path", file.toString(),
                ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
        )));
        assertTrue(read.success(), read.error());

        Thread.sleep(20);
        Files.writeString(file, "external");

        ToolResult write = writeFileTool.handle(new java.util.HashMap<>(Map.of(
                "path", file.toString(),
                "content", "replacement",
                ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
        )));

        assertTrue(write.success(), write.error());
        Map<String, Object> response = parseContent(write);
        assertTrue(response.get("warning").toString().contains("modified since"));
    }

    @Test
    void partialReadThenFullOverwriteReturnsWarning() throws IOException {
        Path file = tempDir.resolve("partial.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\n");
        ToolResult read = readFileTool.handle(new java.util.HashMap<>(Map.of(
                "path", file.toString(),
                "offset", 2,
                "limit", 1,
                ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
        )));
        assertTrue(read.success(), read.error());

        ToolResult write = writeFileTool.handle(new java.util.HashMap<>(Map.of(
                "path", file.toString(),
                "content", "replacement",
                ToolCallbackFactory.SESSION_ID_KEY, SESSION_ID
        )));

        assertTrue(write.success(), write.error());
        Map<String, Object> response = parseContent(write);
        assertTrue(response.get("warning").toString().contains("partially read"));
    }

    @Test
    void testSearchFiles() throws IOException {
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "hello world");
        Files.writeString(file2, "hello there");

        Map<String, Object> args = Map.of(
            "path", tempDir.toString(),
            "pattern", "hello"
        );

        ToolResult result = searchFilesTool.handle(args);
        assertTrue(result.success());
        String content = extractContent(result);
        assertTrue(content.contains("file1.txt") || content.contains("file2.txt"));
    }

    @Test
    void testSearchFilesWithGlob() throws IOException {
        Path txtFile = tempDir.resolve("test.txt");
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(txtFile, "pattern here");
        Files.writeString(javaFile, "pattern here too");

        Map<String, Object> args = Map.of(
            "path", tempDir.toString(),
            "pattern", "pattern",
            "glob", "*.java"
        );

        ToolResult result = searchFilesTool.handle(args);
        assertTrue(result.success());
        String content = extractContent(result);
        assertTrue(content.contains("Test.java"));
        assertFalse(content.contains("test.txt"));
    }

    @Test
    void testListFiles() throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "content a");
        Files.writeString(file2, "content b");

        Map<String, Object> args = Map.of(
            "path", tempDir.toString(),
            "glob", "*.txt"
        );

        ToolResult result = listFilesTool.handle(args);
        assertTrue(result.success());
        String content = extractContent(result);
        assertTrue(content.contains("a.txt"));
        assertTrue(content.contains("b.txt"));
    }

    @Test
    void listFilesSupportsPathAwareGlobAndMetadata() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/App.java"), "class App {}");
        Files.writeString(tempDir.resolve("App.java"), "class Root {}");
        Files.writeString(tempDir.resolve("notes.txt"), "notes");

        ToolResult result = listFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "glob", "src/**/*.java"
        ));

        assertTrue(result.success(), result.error());
        Map<String, Object> response = parseContent(result);
        List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
        assertEquals(1, files.size());
        assertTrue(files.get(0).get("path").toString().endsWith("src/main/java/App.java"));
        assertEquals(1, response.get("total"));
        assertEquals(1, response.get("returned"));
        assertEquals(100, response.get("limit"));
        assertEquals(false, response.get("truncated"));
    }

    @Test
    void globMatcherUsesRecursiveBasenameAndGlobstarIncludesRootFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("root.txt"), "root");
        Files.writeString(tempDir.resolve("nested/child.txt"), "child");
        Files.writeString(tempDir.resolve("nested/child.md"), "child");

        ToolResult txtResult = listFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "glob", "*.txt"
        ));
        assertTrue(txtResult.success(), txtResult.error());
        String txtContent = txtResult.content();
        assertTrue(txtContent.contains("root.txt"));
        assertTrue(txtContent.contains("child.txt"));
        assertFalse(txtContent.contains("child.md"));

        ToolResult allResult = listFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "glob", "**/*"
        ));
        assertTrue(allResult.success(), allResult.error());
        String allContent = allResult.content();
        assertTrue(allContent.contains("root.txt"));
        assertTrue(allContent.contains("child.txt"));
        assertTrue(allContent.contains("child.md"));
    }

    @Test
    void listFilesRejectsMissingOrFileRoot() throws IOException {
        ToolResult missing = listFilesTool.handle(Map.of("path", tempDir.resolve("missing").toString()));
        assertFalse(missing.success());
        assertTrue(missing.error().contains("Path not found"));

        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "content");
        ToolResult notDirectory = listFilesTool.handle(Map.of("path", file.toString()));
        assertFalse(notDirectory.success());
        assertTrue(notDirectory.error().contains("not a directory"));
    }

    @Test
    void listFilesSkipsHiddenAndIgnoredDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve(".hidden"));
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("visible.txt"), "visible");
        Files.writeString(tempDir.resolve(".hidden/secret.txt"), "secret");
        Files.writeString(tempDir.resolve("target/generated.txt"), "generated");

        ToolResult result = listFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "glob", "*.txt"
        ));

        assertTrue(result.success(), result.error());
        String content = result.content();
        assertTrue(content.contains("visible.txt"));
        assertFalse(content.contains("secret.txt"));
        assertFalse(content.contains("generated.txt"));
    }

    @Test
    void listFilesReportsTruncation() throws Exception {
        for (int i = 0; i < 101; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), "content");
        }

        ToolResult result = listFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "glob", "*.txt"
        ));

        assertTrue(result.success(), result.error());
        Map<String, Object> response = parseContent(result);
        assertEquals(101, response.get("total"));
        assertEquals(100, response.get("returned"));
        assertEquals(true, response.get("truncated"));
    }

    @Test
    void searchFilesTargetFilesUsesPathAwareGlobAndPagination() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.writeString(tempDir.resolve("src/main/java/App.java"), "class App {}");
        Files.writeString(tempDir.resolve("src/test/java/AppTest.java"), "class AppTest {}");
        Files.writeString(tempDir.resolve("README.md"), "readme");

        ToolResult result = searchFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "target", "files",
            "pattern", "src/**/*.java",
            "offset", 0,
            "limit", 1
        ));

        assertTrue(result.success(), result.error());
        Map<String, Object> response = parseContent(result);
        List<String> files = (List<String>) response.get("files");
        assertEquals(1, files.size());
        assertEquals(2, response.get("total"));
        assertEquals(1, response.get("returned"));
        assertEquals(true, response.get("truncated"));
    }

    @Test
    void searchFilesFilesOnlyReportsUniqueFileTotal() throws Exception {
        Files.writeString(tempDir.resolve("one.txt"), "hit\nhit\n");
        Files.writeString(tempDir.resolve("two.txt"), "hit\n");

        ToolResult result = searchFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "pattern", "hit",
            "output_mode", "files_only",
            "limit", 1
        ));

        assertTrue(result.success(), result.error());
        Map<String, Object> response = parseContent(result);
        List<String> files = (List<String>) response.get("files");
        assertEquals(1, files.size());
        assertEquals(2, response.get("total"));
        assertEquals(1, response.get("returned"));
        assertEquals(true, response.get("truncated"));
    }

    @Test
    void searchFilesCountReportsMatchesAndFiles() throws Exception {
        Files.writeString(tempDir.resolve("one.txt"), "hit\nhit\n");
        Files.writeString(tempDir.resolve("two.txt"), "hit\n");

        ToolResult result = searchFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "pattern", "hit",
            "output_mode", "count"
        ));

        assertTrue(result.success(), result.error());
        Map<String, Object> response = parseContent(result);
        assertEquals(3, response.get("totalMatches"));
        assertEquals(2, response.get("totalFiles"));
    }

    @Test
    void searchFilesValidatesArgumentsAndRegex() {
        assertFalse(searchFilesTool.handle(Map.of("path", tempDir.toString(), "pattern", "hit", "offset", -1)).success());
        assertFalse(searchFilesTool.handle(Map.of("path", tempDir.toString(), "pattern", "hit", "limit", 0)).success());
        assertFalse(searchFilesTool.handle(Map.of("path", tempDir.toString(), "pattern", "hit", "context", -1)).success());
        ToolResult invalidRegex = searchFilesTool.handle(Map.of("path", tempDir.toString(), "pattern", "["));
        assertFalse(invalidRegex.success());
        assertTrue(invalidRegex.error().contains("Invalid regex"));
    }

    @Test
    void searchFilesSkipsBinaryFiles() throws Exception {
        Files.writeString(tempDir.resolve("text.txt"), "needle");
        Files.write(tempDir.resolve("image.png"), "needle".getBytes());

        ToolResult result = searchFilesTool.handle(Map.of(
            "path", tempDir.toString(),
            "pattern", "needle",
            "glob", "*"
        ));

        assertTrue(result.success(), result.error());
        String content = result.content();
        assertTrue(content.contains("text.txt"));
        assertFalse(content.contains("image.png"));
    }

    @Test
    void testReadFileMissingPath() {
        Map<String, Object> args = Map.of();

        ToolResult result = readFileTool.handle(args);
        assertFalse(result.success());
        assertTrue(result.error().contains("required"));
    }

    private Map<String, Object> parseContent(ToolResult result) throws IOException {
        return mapper.readValue(result.content(), Map.class);
    }

    private String extractContent(ToolResult result) {
        if (result.content() == null) return "";
        try {
            Map<String, Object> map = mapper.readValue(result.content(), Map.class);
            Object content = map.get("content");
            return content != null ? content.toString() : result.content();
        } catch (Exception e) {
            return result.content();
        }
    }
}
