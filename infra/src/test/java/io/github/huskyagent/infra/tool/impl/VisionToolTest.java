package io.github.huskyagent.infra.tool.impl;

import com.sun.net.httpserver.HttpServer;
import io.github.huskyagent.infra.ai.AuxiliaryClient;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.workspace.LocalWorkspace;
import io.github.huskyagent.infra.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisionToolTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    @TempDir
    Path tempDir;

    @Test
    void registersVisionAnalyzeTool() {
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolDefinition definition = tool.getTools().get(0);

        assertEquals("vision_analyze", definition.name());
        assertEquals(Toolset.VISION, definition.toolset());
        assertTrue(definition.parametersSchema().toString().contains("image_url"));
        assertTrue(definition.parametersSchema().toString().contains("question"));
    }

    @Test
    void failsWhenImageUrlIsMissing() {
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of("question", "what is this?"));

        assertFalse(result.success());
        assertEquals("image_url is required", result.error());
        assertFalse(result.retryable());
    }

    @Test
    void failsWhenQuestionIsMissing() {
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of("image_url", "/tmp/a.png"));

        assertFalse(result.success());
        assertEquals("question is required", result.error());
        assertFalse(result.retryable());
    }

    @Test
    void rejectsUnsupportedScheme() {
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", "ftp://example.com/a.png",
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("Unsupported image source scheme"));
    }

    @Test
    void rejectsNonImageLocalFile() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "not image");
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("Unsupported image MIME type"));
    }

    @Test
    void rejectsRenamedTextFileWithImageExtension() throws Exception {
        Path file = tempDir.resolve("note.png");
        Files.writeString(file, "not image");
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("do not match MIME type"));
    }

    @Test
    void rejectsOversizedImage() throws Exception {
        Path file = tempDir.resolve("large.png");
        Files.write(file, new byte[10 * 1024 * 1024 + 1]);
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("too large"));
    }

    @Test
    void rejectsUrlContainingSecret() {
        VisionTool tool = new VisionTool(mock(AuxiliaryClient.class), new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", "https://example.com/a.png?token=abc123",
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("API key or token"));
    }

    @Test
    void rejectsPrivateNetworkUrl() {
        VisionTool tool = toolWithSafety(mock(AuxiliaryClient.class), false);

        ToolResult result = tool.handle(Map.of(
                "image_url", "https://example.com/a.png",
                "question", "what is this?"
        ));

        assertFalse(result.success());
        assertTrue(result.error().contains("private or internal"));
    }

    @Test
    void analyzesValidLocalImage() throws Exception {
        Path file = tempDir.resolve("image.png");
        Files.write(file, PNG_BYTES);
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/png"), eq("describe it")))
                .thenReturn("a png image");
        VisionTool tool = new VisionTool(auxiliaryClient, new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "describe it"
        ));

        assertTrue(result.success());
        assertTrue(result.content().contains("a png image"));
        assertTrue(result.content().contains("image/png"));
        verify(auxiliaryClient).analyzeImage(any(byte[].class), eq("image/png"), eq("describe it"));
    }

    @Test
    void analyzesFileUriImage() throws Exception {
        Path file = tempDir.resolve("image.png");
        Files.write(file, PNG_BYTES);
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/png"), eq("describe it")))
                .thenReturn("a png image");
        VisionTool tool = new VisionTool(auxiliaryClient, new LocalWorkspace());

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toUri().toString(),
                "question", "describe it"
        ));

        assertTrue(result.success());
        verify(auxiliaryClient).analyzeImage(any(byte[].class), eq("image/png"), eq("describe it"));
    }

    @Test
    void normalizesWorkspaceProbedMimeType() throws Exception {
        Path file = tempDir.resolve("image.bin");
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/jpeg"), eq("describe it")))
                .thenReturn("a jpeg image");
        VisionTool tool = new VisionTool(auxiliaryClient, new ProbeWorkspace("image/jpg"));

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "describe it"
        ));

        assertTrue(result.success());
        verify(auxiliaryClient).analyzeImage(any(byte[].class), eq("image/jpeg"), eq("describe it"));
    }

    @Test
    void fallsBackToExtensionWhenWorkspaceMimeProbeIsBlank() throws Exception {
        Path file = tempDir.resolve("image.png");
        Files.write(file, PNG_BYTES);
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/png"), eq("describe it")))
                .thenReturn("a png image");
        VisionTool tool = new VisionTool(auxiliaryClient, new ProbeWorkspace(null));

        ToolResult result = tool.handle(Map.of(
                "image_url", file.toString(),
                "question", "describe it"
        ));

        assertTrue(result.success());
        verify(auxiliaryClient).analyzeImage(any(byte[].class), eq("image/png"), eq("describe it"));
    }

    @Test
    void analyzesHttpImage() throws Exception {
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/png"), eq("describe it")))
                .thenReturn("a remote png image");
        try (TestImageServer server = TestImageServer.start()) {
            server.image("/image.png", PNG_BYTES, "image/png");
            VisionTool tool = toolWithSafety(auxiliaryClient, true);

            ToolResult result = tool.handle(Map.of(
                    "image_url", server.url("/image.png"),
                    "question", "describe it"
            ));

            assertTrue(result.success());
            assertTrue(result.content().contains("a remote png image"));
            verify(auxiliaryClient).analyzeImage(any(byte[].class), eq("image/png"), eq("describe it"));
        }
    }

    @Test
    void followsSafeRedirects() throws Exception {
        AuxiliaryClient auxiliaryClient = mock(AuxiliaryClient.class);
        when(auxiliaryClient.analyzeImage(any(byte[].class), eq("image/png"), eq("describe it")))
                .thenReturn("redirected image");
        try (TestImageServer server = TestImageServer.start()) {
            server.redirect("/redirect", "/image.png");
            server.image("/image.png", PNG_BYTES, "image/png");
            VisionTool tool = toolWithSafety(auxiliaryClient, true);

            ToolResult result = tool.handle(Map.of(
                    "image_url", server.url("/redirect"),
                    "question", "describe it"
            ));

            assertTrue(result.success());
            assertTrue(result.content().contains("redirected image"));
        }
    }

    @Test
    void rejectsHttpNonImageContent() throws Exception {
        try (TestImageServer server = TestImageServer.start()) {
            server.image("/text", "hello".getBytes(), "text/plain");
            VisionTool tool = toolWithSafety(mock(AuxiliaryClient.class), true);

            ToolResult result = tool.handle(Map.of(
                    "image_url", server.url("/text"),
                    "question", "describe it"
            ));

            assertFalse(result.success());
            assertTrue(result.error().contains("Unsupported image MIME type"));
        }
    }

    private VisionTool toolWithSafety(AuxiliaryClient auxiliaryClient, boolean safe) {
        return new VisionTool(auxiliaryClient, new LocalWorkspace(), HttpClient.newHttpClient(), new VisionTool.UrlSafetyPolicy() {
            @Override
            public boolean containsSecret(String url) {
                return url.contains("token=");
            }

            @Override
            public boolean isSafeUrl(String url) {
                return safe;
            }
        });
    }

    private record ProbeWorkspace(String contentType) implements Workspace {
        private final static LocalWorkspace LOCAL = new LocalWorkspace();

        @Override
        public Path resolve(String path) {
            return LOCAL.resolve(path);
        }

        @Override
        public Path root() {
            return LOCAL.root();
        }

        @Override
        public boolean exists(Path path) {
            return LOCAL.exists(path);
        }

        @Override
        public boolean isDirectory(Path path) {
            return LOCAL.isDirectory(path);
        }

        @Override
        public boolean isRegularFile(Path path) {
            return LOCAL.isRegularFile(path);
        }

        @Override
        public boolean isSymbolicLink(Path path) {
            return LOCAL.isSymbolicLink(path);
        }

        @Override
        public long size(Path path) throws IOException {
            return LOCAL.size(path);
        }

        @Override
        public String readString(Path path) throws IOException {
            return LOCAL.readString(path);
        }

        @Override
        public InputStream newInputStream(Path path) throws IOException {
            return LOCAL.newInputStream(path);
        }

        @Override
        public String probeContentType(Path path) {
            return contentType;
        }

        @Override
        public List<String> readAllLines(Path path) throws IOException {
            return LOCAL.readAllLines(path);
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            LOCAL.createDirectories(dir);
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            LOCAL.writeString(path, content);
        }

        @Override
        public void delete(Path path) throws IOException {
            LOCAL.delete(path);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return LOCAL.deleteIfExists(path);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            LOCAL.move(source, target);
        }

        @Override
        public List<Path> walkFiles(Path root) throws IOException {
            return LOCAL.walkFiles(root);
        }

        @Override
        public long getLastModifiedTime(Path path) throws IOException {
            return LOCAL.getLastModifiedTime(path);
        }

        @Override
        public Path toRealPath(Path path) throws IOException {
            return LOCAL.toRealPath(path);
        }
    }

    private static class TestImageServer implements AutoCloseable {
        private final HttpServer server;

        private TestImageServer(HttpServer server) {
            this.server = server;
        }

        static TestImageServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            return new TestImageServer(server);
        }

        void image(String path, byte[] body, String contentType) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
        }

        void redirect(String path, String location) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
