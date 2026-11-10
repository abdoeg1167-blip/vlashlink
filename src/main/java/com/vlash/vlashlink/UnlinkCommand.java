package com.vlash.vlashlink;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.OptionalLong;

/**
 * VlashLink - by Vlash.
 * /unlink - removes the link and resets the Discord nickname.
 */
public final class UnlinkCommand implements SimpleCommand {

    private final Config config;
    private final LinkManager links;
    private final DiscordService discord;

    public UnlinkCommand(Config config, LinkManager links, DiscordService discord) {
        this.config = config;
        this.links = links;
        this.discord = discord;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(config.component("game.only-players"));
            return;
        }

        OptionalLong discordId = links.unlink(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(config.component("game.not-linked"));
            return;
        }

        player.sendMessage(config.component("game.unlink-success"));

        // Reset the Discord nickname (best effort)
        discord.updateNickname(discordId.getAsLong(), null, () -> {}, error -> {});
    }
}
