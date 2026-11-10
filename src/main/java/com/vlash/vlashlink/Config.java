package com.vlash.vlashlink;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * VlashLink - by Vlash.
 * Loads config.properties (UTF-8). Missing keys fall back to the defaults bundled in the jar.
 */
public final class Config {

    private final Properties props;

    private Config(Properties props) {
        this.props = props;
    }

    public static Config load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);

        Properties defaults = new Properties();
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IOException("Bundled config.properties not found in the jar");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }
        }

        Path file = dataDir.resolve("config.properties");
        if (Files.notExists(file)) {
            try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
                if (in != null) {
                    Files.copy(in, file);
                }
            }
        }

        Properties props = new Properties(defaults);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return new Config(props);
    }

    public String get(String key) {
        return props.getProperty(key, "").strip();
    }

    public long getLong(String key, long fallback) {
        try {
            return Long.parseLong(get(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Returns the message with {placeholders} replaced. Pass pairs: name, value, name, value... */
    public String format(String key, String... replacements) {
        String text = get(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return text;
    }

    /** In-game message (prefix + MiniMessage). */
    public Component component(String key, String... replacements) {
        return MiniMessage.miniMessage().deserialize(get("game.prefix") + format(key, replacements));
    }
}
