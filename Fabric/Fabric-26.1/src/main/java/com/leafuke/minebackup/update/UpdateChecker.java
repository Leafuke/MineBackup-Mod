package com.leafuke.minebackup.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leafuke.minebackup.MineBackup;
import com.leafuke.minebackup.ModInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class UpdateChecker {
    private static final URI RELEASE_API = URI.create(
            "https://api.github.com/repos/Leafuke/MineBackup-Mod/releases/latest");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CompletableFuture<Optional<Result>> check() {
        HttpRequest request = HttpRequest.newBuilder(RELEASE_API)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MineBackup-Mod/" + ModInfo.version())
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse)
                .exceptionally(exception -> {
                    MineBackup.LOGGER.warn("Failed to check MineBackup updates", exception);
                    return Optional.empty();
                });
    }

    private Optional<Result> parseResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            MineBackup.LOGGER.warn("MineBackup update check returned HTTP {}.", response.statusCode());
            return Optional.empty();
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("tag_name") || !json.has("html_url")) {
                MineBackup.LOGGER.warn("MineBackup update response is missing required fields.");
                return Optional.empty();
            }

            String latestRaw = json.get("tag_name").getAsString();
            URI releaseUri = URI.create(json.get("html_url").getAsString());
            Optional<VersionNumber> latest = VersionNumber.parse(latestRaw);
            Optional<VersionNumber> current = VersionNumber.parse(ModInfo.version());
            if (latest.isEmpty() || current.isEmpty() || !isTrustedReleaseUri(releaseUri)) {
                MineBackup.LOGGER.warn("MineBackup update response contains invalid version or URL.");
                return Optional.empty();
            }

            return Optional.of(new Result(
                    latestRaw,
                    releaseUri,
                    latest.get().compareTo(current.get()) > 0));
        } catch (RuntimeException exception) {
            MineBackup.LOGGER.warn("Failed to parse MineBackup update response", exception);
            return Optional.empty();
        }
    }

    private static boolean isTrustedReleaseUri(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost());
    }

    public record Result(String latestVersion, URI releaseUri, boolean updateAvailable) {
    }
}
