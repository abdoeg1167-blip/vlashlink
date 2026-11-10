package com.vlash.vlashlink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * VlashLink - by Vlash.
 * /link [code]  - links the player's Minecraft account with the Discord account that owns the code.
 */
public final class LinkCommand implements SimpleCommand {

    private final Config config;
    private final LinkManager links;
    private final DiscordService discord;
    private final Logger logger;

    public LinkCommand(Config config, LinkManager links, DiscordService discord, Logger logger) {
        this.config = config;
        this.links = links;
        this.discord = discord;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(config.component("game.only-players"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length != 1) {
            player.sendMessage(config.component("game.usage"));
            return;
        }

        UUID uuid = player.getUniqueId();
        String name = player.getUsername();

        if (links.getDiscordId(uuid).isPresent()) {
            player.sendMessage(config.component("game.already-linked"));
            return;
        }

        OptionalLong discordId = links.consumeCode(args[0]);
        if (discordId.isEmpty()) {
            player.sendMessage(config.component("game.invalid-code"));
            return;
        }
        long id = discordId.getAsLong();

        if (links.getByDiscord(id).isPresent()) {
            player.sendMessage(config.component("game.discord-already-linked"));
            return;
        }

        links.link(id, uuid, name);
        player.sendMessage(config.component("game.link-success"));

        // Change the Discord nickname to the in-game name
        discord.updateNickname(id, name,
                () -> player.sendMessage(config.component("game.nickname-updated", "player", name)),
                error -> {
                    logger.warn("Could not change Discord nickname for {}: {}", name, error.getMessage());
                    player.sendMessage(config.component("game.nickname-failed"));
                });
    }
}
