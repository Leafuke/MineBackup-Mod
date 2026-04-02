package com.leafuke.minebackup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdateChecker extends Thread {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String RELEASE_API_URL = "https://api.github.com/repos/Leafuke/MineBackup-Mod/releases/latest";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    public volatile String latestVersion;
    public volatile String latestReleaseUrl;
    public volatile boolean needUpdate;

    public UpdateChecker() {
        super("MineBackup-Update-Checker-Fabric-1.21.11");
        setDaemon(true);
    }

    @Override
    public void run() {
        latestVersion = null;
        latestReleaseUrl = null;
        needUpdate = false;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASE_API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MineBackup-Mod/" + MineBackup.MOD_VERSION)
                    .build();

            HttpResponse<String> response = CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();
            if (statusCode != 200) {
                if (statusCode == 403) {
                    MineBackup.LOGGER.warn("Update check skipped due to GitHub API rate limit (HTTP 403).");
                } else {
                    MineBackup.LOGGER.warn("Update check failed with HTTP status {}.", statusCode);
                }
                return;
            }

            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!jsonObject.has("tag_name") || !jsonObject.has("html_url")) {
                MineBackup.LOGGER.warn("Update check response is missing required fields.");
                return;
            }

            String fetchedVersion = jsonObject.get("tag_name").getAsString();
            String fetchedReleaseUrl = jsonObject.get("html_url").getAsString();
            if (fetchedVersion == null || fetchedVersion.isBlank()
                    || fetchedReleaseUrl == null || fetchedReleaseUrl.isBlank()) {
                MineBackup.LOGGER.warn("Update check response contains empty version or release URL.");
                return;
            }

            latestVersion = fetchedVersion;
            latestReleaseUrl = fetchedReleaseUrl;
            needUpdate = compareVersions(normalize(latestVersion), normalize(MineBackup.MOD_VERSION)) > 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MineBackup.LOGGER.warn("Update check interrupted.");
        } catch (Exception e) {
            MineBackup.LOGGER.warn("Failed to check updates: {}", e.getMessage());
        }
    }

    private static String normalize(String version) {
        if (version == null) {
            return "";
        }
        return version.replaceFirst("^v", "").replaceAll("\\+.*$", "").trim();
    }

    private static int compareVersions(String left, String right) {
        int[] a = parseVersionParts(left);
        int[] b = parseVersionParts(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int[] parseVersionParts(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }
}
