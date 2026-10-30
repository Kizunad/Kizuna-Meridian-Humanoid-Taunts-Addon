package dev.kizuna.meridian.taunts.server;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TauntSettingsTest {
    private static final long DEFAULT_COOLDOWN_TICKS = 120;

    @Test
    public void defaultsSpeakChineseEverySixSecondsOnBothDisplays() throws IOException {
        TauntSettings settings = TauntSettings.parse(defaults());
        assertEquals("zh_cn", settings.language());
        assertEquals(DEFAULT_COOLDOWN_TICKS, settings.cooldownTicks());
        assertTrue(settings.chat());
        assertTrue(settings.bubble());
    }

    @Test
    public void acceptsWholeSecondsAndRejectsUnsafeValues() {
        String valid = settings("\"en_us\"", "3", "true", "false");
        assertEquals(TauntSettings.TICKS_PER_SECOND * 3L, TauntSettings.parse(valid).cooldownTicks());
        assertRejected(settings("\"../zh_cn\"", "6.0", "true", "true"));
        assertRejected(settings("\"ZH_CN\"", "6.0", "true", "true"));
        assertRejected(settings("\"zh_cn\"", "-1.0", "true", "true"));
        assertRejected(settings("\"zh_cn\"", "nan", "true", "true"));
        assertRejected(settings("\"zh_cn\"", "6.0", "\"yes\"", "true"));
        assertRejected("language = \"zh_cn\"\ncooldown_seconds = 6.0\n");
    }

    private static String settings(String language, String cooldown, String chat, String bubble) {
        return "language = " + language + "\ncooldown_seconds = " + cooldown
                + "\n[display]\nchat = " + chat + "\nbubble = " + bubble + "\n";
    }

    private static void assertRejected(String toml) {
        assertThrows(IllegalArgumentException.class, () -> TauntSettings.parse(toml));
    }

    private static String defaults() throws IOException {
        try (InputStream input = TauntSettingsTest.class.getResourceAsStream(TauntSettings.DEFAULT_RESOURCE)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
