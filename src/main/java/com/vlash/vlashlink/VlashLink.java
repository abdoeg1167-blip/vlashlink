package com.vlash.vlashlink;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * VlashLink - links a Minecraft account with a Discord account.
 * Made by Vlash.
 */
@Plugin(
        id = "vlashlink",
        name = "VlashLink",
        version = VlashLink.VERSION,
        description = "Link your Minecraft account with Discord - by Vlash",
        authors = {"Vlash"}
)
public final class VlashLink {

    public static final String VERSION = "1.0.0";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private Config config;
    private LinkManager links;
    private DiscordService discord;

    @Inject
    public VlashLink(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            config = Config.load(dataDirectory);
        } catch (IOException e) {
            logger.error("Could not load config.properties - VlashLink is disabled.", e);
            return;
        }

        links = new LinkManager(dataDirectory, logger, config.getInt("code-expire-minutes", 5));
        discord = new DiscordService(config, links, logger);
        discord.start();

        CommandManager commands = server.getCommandManager();
        commands.register(commands.metaBuilder("link").plugin(this).build(),
                new LinkCommand(config, links, discord, logger));
        commands.register(commands.metaBuilder("unlink").plugin(this).build(),
                new UnlinkCommand(config, links, discord));

        logger.info("VlashLink v{} by Vlash - enabled.", VERSION);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (discord != null) {
            discord.shutdown();
        }
    }

    /** If a linked player changed their Minecraft name, update their Discord nickname too. */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (links == null || discord == null) {
            return;
        }
        Player player = event.getPlayer();
        String currentName = player.getUsername();

        links.getDiscordId(player.getUniqueId()).ifPresent(discordId ->
                links.getByDiscord(discordId).ifPresent(linked -> {
                    if (!linked.name().equals(currentName)) {
                        links.updateName(player.getUniqueId(), currentName);
                        discord.updateNickname(discordId, currentName, () -> {},
                                error -> logger.warn("Could not update Discord nickname for {}: {}",
                                        currentName, error.getMessage()));
                    }
                }));
    }
}
