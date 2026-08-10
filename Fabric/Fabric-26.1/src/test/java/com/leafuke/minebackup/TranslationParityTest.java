package com.leafuke.minebackup;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationParityTest {
    @Test
    void englishAndChineseExposeTheSameKeys() throws Exception {
        assertEquals(keys("en_us.json"), keys("zh_cn.json"));
    }

    private static java.util.Set<String> keys(String fileName) throws Exception {
        String resource = "/assets/minebackup/lang/" + fileName;
        try (var stream = TranslationParityTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .keySet();
        }
    }
}
