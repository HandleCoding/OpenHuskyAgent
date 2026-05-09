package io.github.huskyagent.tui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class AgentCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
        "/help",
        "/exit",
        "/quit",
        "/q",
        "/new",
        "/resume",
        "/rewind",
        "/session",
        "/sessions",
        "/clear",
        "/cd",
        "/pwd",
        "/status",
        "/memory"
    );

    private Path workingDirectory;

    public AgentCompleter() {
        this.workingDirectory = Path.of(System.getProperty("user.dir"));
    }

    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();

        if (buffer.startsWith("/")) {
            completeCommand(buffer, candidates);
        }
        else if (buffer.startsWith("/cd ")) {
            completePath(buffer.substring(4), candidates);
        }
    }

    private void completeCommand(String buffer, List<Candidate> candidates) {
        COMMANDS.stream()
            .filter(cmd -> cmd.startsWith(buffer))
            .forEach(cmd -> candidates.add(new Candidate(cmd)));
    }

    private void completePath(String pathPrefix, List<Candidate> candidates) {
        Path basePath;
        String prefix;

        if (pathPrefix.isEmpty() || pathPrefix.equals("~")) {
            basePath = workingDirectory;
            prefix = "";
        } else if (pathPrefix.startsWith("~")) {
            basePath = Path.of(System.getProperty("user.home"));
            prefix = pathPrefix.substring(1);
        } else if (pathPrefix.contains(File.separator)) {
            int lastSep = pathPrefix.lastIndexOf(File.separator);
            basePath = workingDirectory.resolve(pathPrefix.substring(0, lastSep));
            prefix = pathPrefix.substring(lastSep + 1);
        } else {
            basePath = workingDirectory;
            prefix = pathPrefix;
        }

        File baseDir = basePath.toFile();
        if (!baseDir.isDirectory()) {
            return;
        }

        File[] files = baseDir.listFiles();
        if (files == null) {
            return;
        }

        Stream.of(files)
            .filter(f -> f.getName().startsWith(prefix))
            .forEach(f -> {
                String name = f.getName();
                if (f.isDirectory()) {
                    name += File.separator;
                }
                candidates.add(new Candidate(name));
            });
    }
}
