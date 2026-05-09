package io.github.huskyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsIntegrationTest extends AbstractIntegrationTest {

    @Test
    @Order(1)
    void testWriteFileToolExecution() throws Exception {
        System.out.println("\n📋 Test: write_file tool execution");

        String testFile = tempDir.resolve("test-write.txt").toString();

        var result = toolExecutor.execute("write_file", Map.of(
            "path", testFile,
            "content", "Hello from Tool!\nLine 2\nLine 3"
        ));

        assertTrue(result.success(), "Write should succeed: " + result.error());
        assertTrue(Files.exists(Path.of(testFile)), "File should be created");

        String content = Files.readString(Path.of(testFile));
        assertTrue(content.contains("Hello from Tool"), "Content should match");

        System.out.println("✅ File write succeeded:");
        System.out.println("   File: " + testFile);
        System.out.println("   Content: " + content.substring(0, Math.min(100, content.length())));
    }

    @Test
    @Order(2)
    void testReadFileToolExecution() throws Exception {
        System.out.println("\n📋 Test: read_file tool execution");

        String testFile = tempDir.resolve("test-read.txt").toString();
        Files.writeString(Path.of(testFile), "Content for reading\nSecond line\nThird line");

        var result = toolExecutor.execute("read_file", Map.of("path", testFile));

        assertTrue(result.success(), "Read should succeed: " + result.error());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String content = (String) response.get("content");

        assertTrue(content.contains("Content for reading"), "Should contain expected content");
        assertTrue(content.contains("2\t"), "Should have line numbers");

        System.out.println("✅ File read succeeded:");
        System.out.println("   Total lines: " + response.get("totalLines"));
    }

    @Test
    @Order(3)
    void testReadFileWithPagination() throws Exception {
        System.out.println("\n📋 Test: read_file paged read");

        String testFile = tempDir.resolve("multi-line.txt").toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append("Line ").append(i).append("\n");
        }
        Files.writeString(Path.of(testFile), sb.toString());

        var result = toolExecutor.execute("read_file", Map.of(
            "path", testFile,
            "offset", 50,
            "limit", 10
        ));

        assertTrue(result.success(), "Pagination read should succeed");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        String content = (String) response.get("content");

        assertTrue(content.contains("50"), "Should start at line 50");
        assertTrue(content.contains("59"), "Should end around line 59");

        System.out.println("✅ Paged read succeeded:");
        System.out.println("   Start line: 50, read 10 lines");
    }

    @Test
    @Order(4)
    void testEditFileToolExecution() throws Exception {
        System.out.println("\n📋 Test: edit_file tool execution");

        String testFile = tempDir.resolve("test-edit.txt").toString();
        Files.writeString(Path.of(testFile), "original content here");

        String sessionId = "test-session";
        toolExecutor.execute("read_file", Map.of(
            "path", testFile,
            io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory.SESSION_ID_KEY, sessionId
        ));

        var result = toolExecutor.execute("edit_file", Map.of(
            "path", testFile,
            "old_string", "original",
            "new_string", "modified",
            io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory.SESSION_ID_KEY, sessionId
        ));

        assertTrue(result.success(), "Edit should succeed: " + result.error());

        String content = Files.readString(Path.of(testFile));
        assertEquals("modified content here", content);

        System.out.println("✅ File edit succeeded:");
        System.out.println("   Original content: original content here");
        System.out.println("   New content: modified content here");
    }

    @Test
    @Order(5)
    void testApplyPatchToolExecution() throws Exception {
        System.out.println("\n📋 Test: apply_patch tool execution");

        String testFile = tempDir.resolve("test-apply-patch.txt").toString();
        Files.writeString(Path.of(testFile), "one\ntwo\nfive\n");

        var result = toolExecutor.execute("apply_patch", Map.of(
            "patch", """
                    *** Begin Patch
                    *** Update File: %s
                    @@ insert lines @@
                     one
                     two
                    +three
                    +four
                     five
                    *** End Patch
                    """.formatted(testFile)
        ));

        assertTrue(result.success(), "apply_patch should succeed: " + result.error());
        assertEquals("one\ntwo\nthree\nfour\nfive\n", Files.readString(Path.of(testFile)));
    }

    @Test
    @Order(6)
    void testSearchFilesToolExecution() throws Exception {
        System.out.println("\n📋 Test: search_files tool execution");

        Files.writeString(tempDir.resolve("file1.txt"), "hello world");
        Files.writeString(tempDir.resolve("file2.txt"), "hello there");
        Files.writeString(tempDir.resolve("file3.txt"), "goodbye");

        var result = toolExecutor.execute("search_files", Map.of(
            "path", tempDir.toString(),
            "pattern", "hello"
        ));

        assertTrue(result.success(), "Search should succeed: " + result.error());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        List<Map<String, Object>> matches = (List<Map<String, Object>>) response.get("matches");

        assertTrue(matches.size() >= 2, "Should find at least 2 matches");
        assertEquals(2, response.get("total"));
        assertEquals(2, response.get("returned"));
        assertEquals(false, response.get("truncated"));

        System.out.println("✅ File search succeeded:");
        System.out.println("   Match count: " + matches.size());
        matches.forEach(m -> System.out.println("   - " + m.get("file") + " line " + m.get("line")));
    }

    @Test
    @Order(7)
    void testListFilesToolExecution() throws Exception {
        System.out.println("\n📋 Test: list_files tool execution");

        var result = toolExecutor.execute("list_files", Map.of(
            "path", tempDir.toString(),
            "glob", "*.txt"
        ));

        assertTrue(result.success(), "List should succeed: " + result.error());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(result.content(), Map.class);
        List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");

        assertTrue(files.size() >= 3, "Should find at least 3 txt files");
        assertEquals(files.size(), response.get("returned"));
        assertEquals(100, response.get("limit"));
        assertTrue(response.containsKey("truncated"));

        System.out.println("✅ File list succeeded:");
        System.out.println("   File count: " + files.size());
        files.forEach(f -> System.out.println("   - " + f.get("name")));
    }

    @Test
    @Order(8)
    void testBinaryFileBlocked() throws Exception {
        System.out.println("\n📋 Test: binary file blocked");

        Path binaryFile = tempDir.resolve("test.exe");
        Files.write(binaryFile, new byte[]{0x4D, 0x5A}); // MZ header

        var result = toolExecutor.execute("read_file", Map.of(
            "path", binaryFile.toString()
        ));

        assertFalse(result.success(), "Binary file should be blocked");
        assertTrue(result.error().contains("binary"), "Error should mention binary");

        System.out.println("✅ Binary file blocked:");
        System.out.println("   File: test.exe");
        System.out.println("   Result: " + result.error());
    }

    @Test
    @Order(9)
    void testMultiStepWorkflow() throws Exception {
        System.out.println("\n📋 Test: multi-step workflow");

        String workDir = tempDir.resolve("workflow").toString();
        Files.createDirectories(Path.of(workDir));

        String sessionId = "test-workflow-session";
        var writeResult = toolExecutor.execute("write_file", Map.of(
            "path", workDir + "/config.txt",
            "content", "name=test\nversion=1.0"
        ));
        assertTrue(writeResult.success(), "Write should succeed");

        toolExecutor.execute("read_file", Map.of(
            "path", workDir + "/config.txt",
            io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory.SESSION_ID_KEY, sessionId
        ));

        var editResult = toolExecutor.execute("edit_file", Map.of(
            "path", workDir + "/config.txt",
            "old_string", "version=1.0",
            "new_string", "version=2.0",
            io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory.SESSION_ID_KEY, sessionId
        ));
        assertTrue(editResult.success(), "Edit should succeed");

        var searchResult = toolExecutor.execute("search_files", Map.of(
            "path", workDir,
            "pattern", "version"
        ));
        assertTrue(searchResult.success(), "Search should succeed");

        var listResult = toolExecutor.execute("list_files", Map.of(
            "path", workDir,
            "glob", "*.txt"
        ));
        assertTrue(listResult.success(), "List should succeed");

        String finalContent = Files.readString(Path.of(workDir + "/config.txt"));
        assertTrue(finalContent.contains("version=2.0"), "Version should be updated");

        System.out.println("✅ Multi-step workflow completed:");
        System.out.println("   Final content: " + finalContent);
    }
}
