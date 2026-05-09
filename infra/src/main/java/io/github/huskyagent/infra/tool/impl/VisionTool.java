package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.ai.AuxiliaryClient;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.web.UrlSafety;
import io.github.huskyagent.infra.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class VisionTool implements ToolProvider {

    private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private final AuxiliaryClient auxiliaryClient;
    private final Workspace workspace;
    private final HttpClient httpClient;
    private final UrlSafetyPolicy urlSafetyPolicy;

    @Autowired
    public VisionTool(AuxiliaryClient auxiliaryClient, Workspace workspace) {
        this(auxiliaryClient,
                workspace,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new DefaultUrlSafetyPolicy());
    }

    VisionTool(AuxiliaryClient auxiliaryClient, Workspace workspace, HttpClient httpClient, UrlSafetyPolicy urlSafetyPolicy) {
        this.auxiliaryClient = auxiliaryClient;
        this.workspace = workspace;
        this.httpClient = httpClient;
        this.urlSafetyPolicy = urlSafetyPolicy;
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of(
                "vision_analyze",
                "Analyze an image from a local path, file:// URI, or public HTTP(S) URL. Use this directly for image URLs ending in .jpg/.jpeg/.png/.gif/.webp or when the user asks to inspect an image, screenshot, chart, diagram, or visible text. Do not call web_fetch first for direct image files.",
                Toolset.VISION,
                VisionAnalyzeArgs.class,
                this::handle
        ));
    }

    public ToolResult handle(Map<String, Object> args) {
        String imageUrl = stringArg(args, "image_url");
        String question = stringArg(args, "question");

        if (imageUrl == null || imageUrl.isBlank()) {
            return ToolResult.failure("image_url is required", false, "Provide a local image path or public HTTP(S) image URL");
        }
        if (question == null || question.isBlank()) {
            return ToolResult.failure("question is required", false, "Ask what should be analyzed in the image");
        }

        try {
            ImageSource image = loadImage(imageUrl.trim());
            String analysis = auxiliaryClient.analyzeImage(image.data(), image.mimeType(), question.trim());
            return ToolResult.success(Map.of(
                    "analysis", analysis,
                    "mimeType", image.mimeType(),
                    "sourceType", image.sourceType(),
                    "sizeBytes", image.data().length
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage(), false, "Use a supported image path or public HTTP(S) image URL");
        } catch (Exception e) {
            log.error("vision_analyze failed: {}", e.getMessage());
            return ToolResult.failure("Image analysis failed: " + e.getMessage(), true,
                    "Check the image source and try again");
        }
    }

    private ImageSource loadImage(String source) throws IOException, InterruptedException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return loadRemoteImage(URI.create(source));
        }
        if (looksLikeUnsupportedUri(source)) {
            throw new IllegalArgumentException("Unsupported image source scheme");
        }
        return loadLocalImage(source);
    }

    private ImageSource loadRemoteImage(URI initialUri) throws IOException, InterruptedException {
        URI currentUri = initialUri;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            validateRemoteUri(currentUri);
            HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "image/*")
                    .header("User-Agent", "HuskyVision/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                if (redirectCount == MAX_REDIRECTS) {
                    throw new IllegalArgumentException("Image URL redirected too many times");
                }
                currentUri = redirectedUri(currentUri, response);
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("Image URL returned HTTP status " + status);
            }

            URI finalUri = currentUri;
            String mimeType = response.headers()
                    .firstValue("Content-Type")
                    .map(VisionTool::normalizeMimeType)
                    .orElseGet(() -> mimeTypeFromExtension(finalUri.getPath()));
            enforceSupportedMime(mimeType);
            byte[] data = readLimited(response.body());
            enforceImageSignature(data, mimeType);
            return new ImageSource(data, mimeType, "url");
        }
        throw new IllegalArgumentException("Image URL redirected too many times");
    }

    private void validateRemoteUri(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported image URL scheme");
        }
        String url = uri.toString();
        if (urlSafetyPolicy.containsSecret(url)) {
            throw new IllegalArgumentException("Blocked: URL contains what appears to be an API key or token");
        }
        if (!urlSafetyPolicy.isSafeUrl(url)) {
            throw new IllegalArgumentException("Blocked: URL targets a private or internal network address");
        }
    }

    private static URI redirectedUri(URI currentUri, HttpResponse<?> response) {
        String location = response.headers()
                .firstValue("Location")
                .orElseThrow(() -> new IllegalArgumentException("Image URL redirect missing Location header"));
        return currentUri.resolve(location);
    }

    private static byte[] readLimited(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("Image data is empty");
        }
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_IMAGE_SIZE_BYTES) {
                    throw new IllegalArgumentException("Image is too large; maximum size is 10 MB");
                }
                out.write(buffer, 0, read);
            }
            byte[] data = out.toByteArray();
            enforceSize(data.length);
            return data;
        }
    }

    private ImageSource loadLocalImage(String source) throws IOException {
        Path path = localPath(source);
        if (!workspace.isRegularFile(path)) {
            throw new IllegalArgumentException("Image file is not readable: " + path);
        }
        long size = workspace.size(path);
        enforceSize(size);
        byte[] data = readLimited(workspace.newInputStream(path));
        String mimeType = workspace.probeContentType(path);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = mimeTypeFromExtension(path.getFileName().toString());
        } else {
            mimeType = normalizeMimeType(mimeType);
        }
        enforceSupportedMime(mimeType);
        enforceImageSignature(data, mimeType);
        return new ImageSource(data, mimeType, "file");
    }

    private Path localPath(String source) {
        String path = source;
        if (path.startsWith("file://")) {
            path = URI.create(path).getPath();
        }
        return workspace.resolve(path).normalize();
    }

    private static boolean looksLikeUnsupportedUri(String source) {
        int colon = source.indexOf(':');
        if (colon <= 1) {
            return false;
        }
        String scheme = source.substring(0, colon).toLowerCase(Locale.ROOT);
        return !scheme.equals("file");
    }

    private static void enforceSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Image data is empty");
        }
        if (sizeBytes > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("Image is too large; maximum size is 10 MB");
        }
    }

    private static void enforceSupportedMime(String mimeType) {
        if (mimeType == null || !SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported image MIME type: " + (mimeType != null ? mimeType : "unknown"));
        }
    }

    private static void enforceImageSignature(byte[] data, String mimeType) {
        boolean valid = switch (mimeType) {
            case "image/png" -> startsWith(data, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(data, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(data, 0x47, 0x49, 0x46, 0x38);
            case "image/webp" -> data.length >= 12
                    && startsWith(data, 0x52, 0x49, 0x46, 0x46)
                    && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Image bytes do not match MIME type: " + mimeType);
        }
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMimeType(String value) {
        String mimeType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (mimeType) {
            case "image/jpg" -> "image/jpeg";
            default -> mimeType;
        };
    }

    private static String mimeTypeFromExtension(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String text ? text : null;
    }

    public record VisionAnalyzeArgs(
            @JsonPropertyDescription("Image source to analyze. Supports public http(s) image URLs, file:// URIs, absolute local paths, and ~/ paths.")
            String image_url,
            @JsonPropertyDescription("Specific question or instruction for the visual analysis.")
            String question
    ) {
    }

    interface UrlSafetyPolicy {
        boolean containsSecret(String url);

        boolean isSafeUrl(String url);
    }

    private static class DefaultUrlSafetyPolicy implements UrlSafetyPolicy {
        @Override
        public boolean containsSecret(String url) {
            return UrlSafety.containsSecret(url);
        }

        @Override
        public boolean isSafeUrl(String url) {
            return UrlSafety.isSafeUrl(url);
        }
    }

    private record ImageSource(byte[] data, String mimeType, String sourceType) {
    }
}
