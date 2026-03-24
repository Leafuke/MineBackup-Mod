package com.leafuke.minebackup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class UpdateChecker extends Thread {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String RELEASE_API_URL = "https://api.github.com/repos/Leafuke/MineBackup-Mod/releases/latest";

    public volatile String latestVersion;
    public volatile String latestReleaseUrl;
    public volatile boolean needUpdate;

    public UpdateChecker() {
        super("MineBackup-Update-Checker-NeoForge-1.21");
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder().uri(URI.create(RELEASE_API_URL)).build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            latestVersion = jsonObject.get("tag_name").getAsString();
            latestReleaseUrl = jsonObject.get("html_url").getAsString();
            needUpdate = compareVersions(normalize(latestVersion), normalize(MineBackup.MOD_VERSION)) > 0;
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
