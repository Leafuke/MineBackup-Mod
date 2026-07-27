package com.leafuke.minebackup.dedicated;

import com.leafuke.minebackup.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RestartScriptResolver {
    private static final List<String> WINDOWS_CANDIDATES =
            List.of("start.bat", "start.cmd", "run.bat", "run.cmd");
    private static final List<String> UNIX_CANDIDATES = List.of("start.sh", "run.sh");

    private RestartScriptResolver() {
    }

    public static Resolution resolve(
            Config.DedicatedRestore config,
            Path workingDirectory,
            String osName) {
        Objects.requireNonNull(config, "config");
        Path root = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        if (config.mode() == Config.DedicatedRestoreMode.DISABLED) {
            return Resolution.unavailable("Dedicated restore is disabled");
        }

        boolean windows = Objects.requireNonNull(osName, "osName")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        Path script;
        if (!config.restartScript().isBlank()) {
            Path configured = Path.of(config.restartScript());
            script = configured.isAbsolute() ? configured : root.resolve(configured);
            script = script.toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) {
                return Resolution.unavailable("Configured restart script is not a regular file: " + script);
            }
        } else {
            List<Path> found = new ArrayList<>();
            for (String candidate : windows ? WINDOWS_CANDIDATES : UNIX_CANDIDATES) {
                Path path = root.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    found.add(path.toAbsolutePath().normalize());
                }
            }
            if (found.size() != 1) {
                return Resolution.unavailable(
                        found.isEmpty()
                                ? "No restart script candidate was found"
                                : "Multiple restart script candidates were found; configure one explicitly");
            }
            script = found.getFirst();
        }

        String lowerName = script.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> command;
        if (windows && (lowerName.endsWith(".bat") || lowerName.endsWith(".cmd"))) {
            command = List.of("cmd.exe", "/c", script.toString());
        } else if (!windows && lowerName.endsWith(".sh")) {
            command = List.of("/bin/sh", script.toString());
        } else {
            if (!Files.isExecutable(script)) {
                return Resolution.unavailable("Configured restart file is not executable: " + script);
            }
            command = List.of(script.toString());
        }
        return new Resolution(true, script, List.copyOf(command), "");
    }

    public record Resolution(boolean available, Path script, List<String> command, String reason) {
        public Resolution {
            command = List.copyOf(command);
            reason = reason == null ? "" : reason;
        }

        static Resolution unavailable(String reason) {
            return new Resolution(false, null, List.of(), reason);
        }
    }
}
