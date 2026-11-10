package com.vlash.vlashlink;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;

/**
 * VlashLink - by Vlash.
 * Holds the temporary link codes and the permanent Discord <-> Minecraft links.
 * Links are saved to links.properties as:  discordId=uuid,name
 */
public final class LinkManager {

    public record Linked(UUID uuid, String name) {}

    public record Code(String value, long expiresAt) {}

    private record Pending(long discordId, long expiresAt) {}

    // No 0/O/1/I/L so codes are easy to read
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final Path file;
    private final Logger logger;
    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();

    private final Map<Long, Linked> byDiscord = new HashMap<>();
    private final Map<UUID, Long> byUuid = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();

    public LinkManager(Path dataDir, Logger logger, int ttlMinutes) {
        this.file = dataDir.resolve("links.properties");
        this.logger = logger;
        this.ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
        load();
    }

    // ---------------------------------------------------------------- codes

    /** Creates a code for this Discord user (or returns the one they already have). */
    public synchronized Code createCode(long discordId) {
        purgeExpired();

        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (entry.getValue().discordId() == discordId) {
                return new Code(entry.getKey(), entry.getValue().expiresAt());
            }
        }

        String code;
        do {
            code = randomCode();
        } while (pending.containsKey(code));

        long expiresAt = System.currentTimeMillis() + ttlMillis;
        pending.put(code, new Pending(discordId, expiresAt));
        return new Code(code, expiresAt);
    }

    /** Uses up a code. Returns the Discord id it belongs to, or empty if invalid/expired. */
    public synchronized OptionalLong consumeCode(String code) {
        purgeExpired();
        Pending p = pending.remove(code.trim().toUpperCase(Locale.ROOT));
        return p == null ? OptionalLong.empty() : OptionalLong.of(p.discordId());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt() < now);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- links

    public synchronized void link(long discordId, UUID uuid, String name) {
        byDiscord.put(discordId, new Linked(uuid, name));
        byUuid.put(uuid, discordId);
        save();
    }

    /** Removes the link of this player. Returns the Discord id that was linked, if any. */
    public synchronized OptionalLong unlink(UUID uuid) {
        Long discordId = byUuid.remove(uuid);
        if (discordId == null) {
            return OptionalLong.empty();
        }
        byDiscord.remove(discordId);
        save();
        return OptionalLong.of(discordId);
    }

    public synchronized void updateName(UUID uuid, String name) {
        Long discordId = byUuid.get(uuid);
        if (discordId != null) {
            byDiscord.put(discordId, new Linked(uuid, name));
            save();
        }
    }

    public synchronized Optional<Linked> getByDiscord(long discordId) {
        return Optional.ofNullable(byDiscord.get(discordId));
    }

    public synchronized OptionalLong getDiscordId(UUID uuid) {
        Long id = byUuid.get(uuid);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            logger.error("Could not read links.properties", e);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            try {
                long discordId = Long.parseLong(key);
                String[] parts = props.getProperty(key).split(",", 2);
                UUID uuid = UUID.fromString(parts[0]);
                String name = parts.length > 1 ? parts[1] : "";
                byDiscord.put(discordId, new Linked(uuid, name));
                byUuid.put(uuid, discordId);
            } catch (RuntimeException e) {
                logger.warn("Skipping invalid entry in links.properties: {}", key);
            }
        }
        logger.info("Loaded {} linked account(s).", byDiscord.size());
    }

    private void save() {
        Properties props = new Properties();
        byDiscord.forEach((discordId, linked) ->
                props.setProperty(Long.toString(discordId), linked.uuid() + "," + linked.name()));

        Path tmp = file.resolveSibling("links.properties.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, "VlashLink linked accounts (discordId=uuid,name) - by Vlash");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Could not save links.properties", e);
        }
    }
}
