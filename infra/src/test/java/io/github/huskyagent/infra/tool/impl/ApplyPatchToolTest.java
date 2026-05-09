package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApplyPatchToolTest {

    @TempDir
    Path tempDir;

    private final ApplyPatchTool tool = new ApplyPatchTool(new LocalWorkspace());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolDescriptionShowsRequiredV4aFormat() {
        var definition = tool.getTools().get(0);

        assertTrue(definition.description().contains("*** Begin Patch"));
        assertTrue(definition.description().contains("*** Update File: path"));
        assertTrue(definition.description().contains("Line prefixes"));
        assertTrue(definition.description().contains("Empty lines in patch must match empty lines in the file"));
        assertTrue(definition.description().contains("Optional @@ context @@ hints"));
        assertTrue(definition.description().contains("context/removal lines are used for matching"));
        assertTrue(definition.parametersSchema().toString().contains("*** Begin Patch"));
        assertTrue(definition.parametersSchema().toString().contains("Move File"));
        assertTrue(definition.parametersSchema().toString().contains("Empty lines in patch"));
        assertTrue(definition.parametersSchema().toString().contains("Optional @@ context @@ hints"));
    }

    @Test
    void appliesUpdateAndPreservesBlankLines() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "first\n\nold\nlast\n");

        ToolResult result = tool.handle(Map.of("patch", """
                *** Begin Patch
                *** Update File: %s
                @@ sample @@
                 first

                -old
                +new
                 last
                *** End Patch
                """.formatted(file)));

        assertTrue(result.success(), result.error());
        assertEquals("first\n\nnew\nlast\n", Files.readString(file));
        Map<String, Object> output = mapper.readValue(result.content(), Map.class);
        String diff = (String) output.get("diff");
        assertTrue(diff.contains("-old"));
        assertTrue(diff.contains("+new"));
    }

    @Test
    void validationFailureDoesNotModifyAnyFile() throws Exception {
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(first, "alpha\n");
        Files.writeString(second, "beta\n");

        ToolResult result = tool.handle(Map.of("patch", """
                *** Begin Patch
                *** Update File: %s
                -alpha
                +ALPHA
                *** Update File: %s
                -missing
                +MISSING
                *** End Patch
                """.formatted(first, second)));

        assertFalse(result.success());
        assertEquals("alpha\n", Files.readString(first));
        assertEquals("beta\n", Files.readString(second));
    }

    @Test
    void hunkNotFoundShowsSearchContentAndBlankLines() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "first line\n\nactual content\nlast\n");

        ToolResult result = tool.handle(Map.of("patch", """
                *** Begin Patch
                *** Update File: %s
                @@ sample @@
                 first line

                -old content here
                +new content
                 last
                *** End Patch
                """.formatted(file)));

        assertFalse(result.success());
        assertTrue(result.error().contains("hunk 'sample' not found"));
        assertTrue(result.error().contains("Search content:"));
        assertTrue(result.error().contains("  first line"));
        assertTrue(result.error().contains("  (empty line)"));
        assertTrue(result.error().contains("  old content here"));
        assertTrue(result.error().contains("Tip: Check if context lines match exactly"));
        assertEquals("first line\n\nactual content\nlast\n", Files.readString(file));
    }

    @Test
    void appliesAddDeleteAndMove() throws Exception {
        Path deleteMe = tempDir.resolve("delete.txt");
        Path moveMe = tempDir.resolve("move.txt");
        Path moved = tempDir.resolve("moved.txt");
        Path addMe = tempDir.resolve("add.txt");
        Files.writeString(deleteMe, "delete\n");
        Files.writeString(moveMe, "move\n");

        ToolResult result = tool.handle(Map.of("patch", """
                *** Begin Patch
                *** Add File: %s
                +created
                *** Delete File: %s
                *** Move File: %s -> %s
                *** End Patch
                """.formatted(addMe, deleteMe, moveMe, moved)));

        assertTrue(result.success(), result.error());
        assertEquals("created", Files.readString(addMe));
        assertFalse(Files.exists(deleteMe));
        assertFalse(Files.exists(moveMe));
        assertEquals("move\n", Files.readString(moved));
    }
}
