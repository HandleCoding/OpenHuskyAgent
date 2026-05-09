package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeishuApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeishuCardRenderer cardRenderer = new FeishuCardRenderer(objectMapper);

    @Test
    void markdownTableRendersAsInteractiveTableCard() throws Exception {
        String content = cardRenderer.markdownTableCardContent("""
                Summary before table.

                | Name | Status |
                | --- | --- |
                | Build | **Passing** |
                | Tests | Failed |

                Summary after table.
                """);

        assertNotNull(content);
        JsonNode card = objectMapper.readTree(content);
        JsonNode elements = card.path("elements");
        assertEquals(3, elements.size());
        assertEquals("div", elements.get(0).path("tag").asText());
        assertEquals("Summary before table.", elements.get(0).path("text").path("content").asText());
        assertEquals("table", elements.get(1).path("tag").asText());
        assertEquals("Name", elements.get(1).path("columns").get(0).path("display_name").asText());
        assertEquals("Status", elements.get(1).path("columns").get(1).path("display_name").asText());
        assertEquals("lark_md", elements.get(1).path("columns").get(1).path("data_type").asText());
        assertEquals("Build", elements.get(1).path("rows").get(0).path("col_0").asText());
        assertEquals("**Passing**", elements.get(1).path("rows").get(0).path("col_1").asText());
        assertEquals("Summary after table.", elements.get(2).path("text").path("content").asText());
    }

    @Test
    void mixedTableAndCodeBlockRendersCodeWithoutFences() throws Exception {
        String content = cardRenderer.markdownTableCardContent("""
                **Summary**

                | Name | Status |
                | --- | --- |
                | Build | **Passing** |

                ```java
                System.out.println("ok");
                ```
                """);

        assertNotNull(content);
        JsonNode elements = objectMapper.readTree(content).path("elements");
        assertEquals(3, elements.size());
        assertEquals("lark_md", elements.get(0).path("text").path("tag").asText());
        assertEquals("table", elements.get(1).path("tag").asText());
        assertEquals("lark_md", elements.get(2).path("text").path("tag").asText());
        String codeContent = elements.get(2).path("text").path("content").asText();
        assertTrue(codeContent.contains("System.out.println(\"ok\");"));
        assertTrue(codeContent.startsWith("```java"));
    }

    @Test
    void plainMarkdownDoesNotRenderCard() throws Exception {
        assertNull(cardRenderer.markdownTableCardContent("**hello**\n\n- item"));
    }

    @Test
    void pipeTextInsideCodeBlockDoesNotRenderCard() throws Exception {
        String content = cardRenderer.markdownTableCardContent("""
                ```
                | Name | Status |
                | --- | --- |
                | Build | Passing |
                ```
                """);

        assertNull(content);
    }

    @Test
    void incompletePipeBlockDoesNotRenderCard() throws Exception {
        String content = cardRenderer.markdownTableCardContent("""
                | Name | Status |
                | Build | Passing |
                """);

        assertNull(content);
    }

    @Test
    void splitByTableLimit_noTable_returnsSingleChunk() {
        String text = "Hello\n\nWorld";
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void splitByTableLimit_underLimit_returnsSingleChunk() {
        String text = """
                intro
                | A | B |
                | --- | --- |
                | 1 | 2 |

                | C | D |
                | --- | --- |
                | 3 | 4 |

                outro""";
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(1, chunks.size());
    }

    @Test
    void splitByTableLimit_exactLimit_returnsSingleChunk() {
        String table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n";
        String text = table.repeat(4).strip();
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(1, chunks.size());
    }

    @Test
    void splitByTableLimit_overLimit_splitsIntoMultipleChunks() {
        String table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n";
        String text = table.repeat(5).strip();
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(2, chunks.size());
        long firstTableCount = chunks.get(0).lines()
                .filter(l -> l.startsWith("| --- |")).count();
        assertEquals(4, firstTableCount);
        long secondTableCount = chunks.get(1).lines()
                .filter(l -> l.startsWith("| --- |")).count();
        assertEquals(1, secondTableCount);
    }

    @Test
    void splitByTableLimit_9tables_splits_4_4_1() {
        String table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n";
        String text = table.repeat(9).strip();
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(3, chunks.size());
        long[] counts = chunks.stream()
                .mapToLong(c -> c.lines().filter(l -> l.startsWith("| --- |")).count())
                .toArray();
        assertArrayEquals(new long[]{4, 4, 1}, counts);
    }

    @Test
    void splitByTableLimit_tableInsideCodeBlock_notCounted() {
        String text = """
                ```
                | A | B |
                | --- | --- |
                | 1 | 2 |
                ```
                normal text""";
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(1, chunks.size());
    }

    @Test
    void splitByTableLimit_textBeforeAndAfterTable_preserved() {
        String table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n";
        String text = "intro\n\n" + table.repeat(5).strip() + "\n\noutro";
        List<String> chunks = cardRenderer.splitByTableLimit(text, 4);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("intro"));
        assertTrue(chunks.get(1).endsWith("outro"));
    }
}