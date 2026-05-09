package io.github.huskyagent.infra.tool.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class V4aPatchParserTest {

    @Test
    void requiresPatchBoundaries() {
        V4aPatchParser.ParseResult result = V4aPatchParser.parse("*** Update File: a.txt\n-old\n+new\n");

        assertNotNull(result.error());
        assertTrue(result.error().contains("Begin Patch"));
    }

    @Test
    void preservesBlankLinesInHunks() {
        String patch = """
                *** Begin Patch
                *** Update File: a.txt
                @@ block @@
                 first

                 second
                -old
                +new
                *** End Patch
                """;

        V4aPatchParser.ParseResult result = V4aPatchParser.parse(patch);

        assertNull(result.error());
        var lines = result.operations().get(0).hunks().get(0).lines();
        assertTrue(lines.stream().anyMatch(line -> " ".equals(line.prefix()) && line.content().isEmpty()));
    }

    @Test
    void parsesMoveOperation() {
        String patch = """
                *** Begin Patch
                *** Move File: old.txt -> new.txt
                *** End Patch
                """;

        V4aPatchParser.ParseResult result = V4aPatchParser.parse(patch);

        assertNull(result.error());
        assertEquals(V4aPatchParser.OperationType.MOVE, result.operations().get(0).operation());
        assertEquals("new.txt", result.operations().get(0).newPath());
    }
}
