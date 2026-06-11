package io.github.huskyagent.infra.tool.impl;

import io.github.huskyagent.infra.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class FileSafety {

    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "/etc/", "/private/etc/", "/private/var/", "/boot/", "/usr/lib/systemd/");

    private static final Set<String> HOME_PROTECTED_PREFIXES = Set.of(
            ".ssh", ".aws", ".gnupg", ".kube", ".docker",
            ".config/gh", ".config/gcloud");

    private static final Set<String> PROTECTED_EXACT_NAMES = Set.of(
            ".netrc", ".pgpass", ".npmrc", ".pypirc", ".git-credentials",
            "id_rsa", "id_ed25519", "id_dsa", "id_ecdsa",
            "authorized_keys", "credentials", "credentials.json",
            "token", "tokens.json", "auth.json", "mcp-tokens",
            ".anthropic_oauth.json", "google_oauth.json",
            "webhook_subscriptions.json", "bws_cache.json");

    private static final Set<String> READ_BLOCKED_ENV_NAMES = Set.of(
            ".env", ".env.local", ".env.development", ".env.production",
            ".env.test", ".env.staging", ".envrc");

    private FileSafety() {}

    static Path resolve(Workspace workspace, String path) {
        return workspace.resolve(path).toAbsolutePath().normalize();
    }

    static Path canonicalForAccess(Workspace workspace, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (workspace.exists(normalized)) {
            return workspace.toRealPath(normalized).toAbsolutePath().normalize();
        }
        Path parent = normalized.getParent();
        if (parent != null && workspace.exists(parent)) {
            return workspace.toRealPath(parent).toAbsolutePath().normalize()
                    .resolve(normalized.getFileName())
                    .normalize();
        }
        return normalized;
    }

    static String checkReadAllowed(Workspace workspace, String displayPath, Path path) throws IOException {
        Path canonical = canonicalForAccess(workspace, path);
        if (FileUtils.BLOCKED_DEVICE_PATHS.contains(path.toAbsolutePath().normalize().toString())
                || FileUtils.BLOCKED_DEVICE_PATHS.contains(canonical.toString())) {
            return "Read denied: '" + displayPath + "' is a device file";
        }
        if (isProtectedPath(canonical)) {
            return "Read denied: '" + displayPath + "' is a protected path";
        }
        if (isEnvFile(canonical)) {
            return "Read denied: '" + displayPath + "' appears to be an environment secrets file";
        }
        if (isCredentialLike(canonical)) {
            return "Read denied: '" + displayPath + "' appears to contain credentials or tokens";
        }
        return null;
    }

    static String checkWriteAllowed(Workspace workspace, String displayPath, Path path) throws IOException {
        String denied = checkMutationAllowed(workspace, displayPath, path);
        if (denied != null) {
            return denied.replace("Mutation denied", "Write denied");
        }
        Path canonical = canonicalForAccess(workspace, path);
        if (FileUtils.isBinaryFile(canonical)) {
            return "Write denied: '" + displayPath + "' appears to be a binary file";
        }
        return null;
    }

    static String checkMutationAllowed(Workspace workspace, String displayPath, Path path) throws IOException {
        Path canonical = canonicalForAccess(workspace, path);
        if (FileUtils.BLOCKED_DEVICE_PATHS.contains(path.toAbsolutePath().normalize().toString())
                || FileUtils.BLOCKED_DEVICE_PATHS.contains(canonical.toString())) {
            return "Mutation denied: '" + displayPath + "' is a device file";
        }
        if (isProtectedPath(canonical) || isEnvFile(canonical) || isCredentialLike(canonical)) {
            return "Mutation denied: '" + displayPath + "' is a protected or credential path";
        }
        return null;
    }

    static boolean canReadContent(Workspace workspace, Path path) {
        try {
            return checkReadAllowed(workspace, path.toString(), path) == null;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isProtectedPath(Path path) {
        if (isUnderSystemTemp(path)) {
            return false;
        }
        String normalized = path.toAbsolutePath().normalize().toString();
        if (PROTECTED_PREFIXES.stream().anyMatch(normalized::startsWith)) {
            return true;
        }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (path.toAbsolutePath().normalize().startsWith(home)) {
            Path relative = home.relativize(path.toAbsolutePath().normalize());
            String relativeText = relative.toString().replace('\\', '/');
            return HOME_PROTECTED_PREFIXES.stream()
                    .anyMatch(prefix -> relativeText.equals(prefix) || relativeText.startsWith(prefix + "/"));
        }
        return false;
    }

    private static boolean isUnderSystemTemp(Path path) {
        try {
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (Files.exists(temp)) {
                temp = temp.toRealPath().toAbsolutePath().normalize();
            }
            return path.toAbsolutePath().normalize().startsWith(temp);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isEnvFile(Path path) {
        String name = lowerFileName(path);
        return READ_BLOCKED_ENV_NAMES.contains(name);
    }

    private static boolean isCredentialLike(Path path) {
        String name = lowerFileName(path);
        if (PROTECTED_EXACT_NAMES.contains(name)) {
            return true;
        }
        return name.endsWith(".pem") || name.endsWith(".key")
                || name.endsWith(".p12") || name.endsWith(".pfx");
    }

    private static String lowerFileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
    }
}
