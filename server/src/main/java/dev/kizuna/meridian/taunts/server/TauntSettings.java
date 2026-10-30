package dev.kizuna.meridian.taunts.server;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * meridian-humanoid-taunts.toml：台词语言、发言冷却与两种显示开关。
 * 语言代码决定读取哪份台词文件，所以只接受小写字母与下划线，不能拼出别的资源路径。
 */
public record TauntSettings(String language, double cooldownSeconds, boolean chat, boolean bubble) {
    public static final String DEFAULT_RESOURCE = "/meridian-humanoid-taunts.toml";
    public static final int TICKS_PER_SECOND = 20;
    public static final double MAX_COOLDOWN_SECONDS = 3600;
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]+(_[a-z]+)?");

    public static TauntSettings load(Path path) {
        // 首次启动导出可编辑默认值；以后读取已有配置，不重写玩家的调整。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                try (InputStream input = TauntSettings.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (input == null) {
                        throw TauntErrors.invalid(TauntErrors.SETTINGS_RESOURCE, DEFAULT_RESOURCE);
                    }
                    Files.copy(input, path);
                }
            }
            return parse(Files.readString(path));
        } catch (IOException failure) {
            throw new IllegalStateException(TauntErrors.SETTINGS_READ + ": " + path, failure);
        }
    }

    public static TauntSettings parse(String text) {
        TomlParseResult config = Toml.parse(text);
        if (config.hasErrors()) {
            throw TauntErrors.invalid(TauntErrors.SETTINGS_FORMAT, config.errors().toString());
        }
        String language = config.getString("language");
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            throw TauntErrors.invalid(TauntErrors.SETTINGS_LANGUAGE, String.valueOf(language));
        }
        // 整数与小数秒都接受。
        Object configuredCooldown = config.get("cooldown_seconds");
        if (!(configuredCooldown instanceof Number cooldownNumber)) {
            throw TauntErrors.invalid(TauntErrors.SETTINGS_COOLDOWN, String.valueOf(configuredCooldown));
        }
        double cooldown = cooldownNumber.doubleValue();
        if (!(cooldown >= 0 && cooldown <= MAX_COOLDOWN_SECONDS)) {
            throw TauntErrors.invalid(TauntErrors.SETTINGS_COOLDOWN, String.valueOf(cooldown));
        }
        boolean chat = displaySwitch(config, "display.chat");
        boolean bubble = displaySwitch(config, "display.bubble");
        return new TauntSettings(language, cooldown, chat, bubble);
    }

    public long cooldownTicks() {
        return Math.round(cooldownSeconds * TICKS_PER_SECOND);
    }

    private static boolean displaySwitch(TomlParseResult config, String key) {
        if (!config.isBoolean(key)) {
            throw TauntErrors.invalid(TauntErrors.SETTINGS_SWITCH, key);
        }
        return config.getBoolean(key);
    }
}
