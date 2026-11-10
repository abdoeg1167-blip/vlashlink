package com.vlash.vlashlink;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * VlashLink - by Vlash.
 * The Discord side: posts the panel, hands out link codes, and changes nicknames.
 */
public final class DiscordService extends ListenerAdapter {

    private static final String BUTTON_ID = "vlashlink:get-code";
    private static final String PANEL_COMMAND = "vlashlink-panel";
    private static final String FOOTER = "VlashLink \u2022 By Vlash";

    private final Config config;
    private final LinkManager links;
    private final Logger logger;

    private JDA jda;

    public DiscordService(Config config, LinkManager links, Logger logger) {
        this.config = config;
        this.links = links;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts the bot. Returns false if the token is missing or login failed. */
    public boolean start() {
        String token = config.get("bot-token");
        if (token.isEmpty() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            logger.warn("No Discord bot token set in config.properties - the Discord side is disabled.");
            return false;
        }
        try {
            jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class))
                    .addEventListeners(this)
                    .build();
            return true;
        } catch (Exception e) {
            logger.error("Could not start the Discord bot: {}", e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    public boolean isOnline() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    private Guild resolveGuild() {
        if (jda == null) {
            return null;
        }
        long guildId = config.getLong("guild-id", 0L);
        if (guildId > 0) {
            return jda.getGuildById(guildId);
        }
        List<Guild> guilds = jda.getGuilds();
        return guilds.size() == 1 ? guilds.get(0) : null;
    }

    // ---------------------------------------------------------------- nickname

    /**
     * Sets the member's nickname in the configured server (null resets it).
     * onFailure is called if the bot can't do it (missing permission, user not in server, ...).
     */
    public void updateNickname(long discordId, String nickname, Runnable onSuccess, Consumer<Throwable> onFailure) {
        Guild guild = resolveGuild();
        if (guild == null) {
            onFailure.accept(new IllegalStateException("Discord server not available (check guild-id and that the bot is online)"));
            return;
        }
        String nick = nickname != null && nickname.length() > 32 ? nickname.substring(0, 32) : nickname;
        guild.retrieveMemberById(discordId).queue(
                member -> member.modifyNickname(nick).queue(done -> onSuccess.run(), onFailure),
                onFailure
        );
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onReady(ReadyEvent event) {
        Guild guild = resolveGuild();
        if (guild == null) {
            logger.error("Could not find your Discord server. Set a valid guild-id in config.properties.");
            return;
        }
        guild.updateCommands()
                .addCommands(
                        Commands.slash(PANEL_COMMAND, "Send the VlashLink panel (get-code button) to this channel")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                )
                .queue(
                        ok -> logger.info("Discord bot online as {} - use /{} in a channel to post the panel.",
                                event.getJDA().getSelfUser().getName(), PANEL_COMMAND),
                        err -> logger.error("Could not register the slash command: {}", err.getMessage())
                );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!PANEL_COMMAND.equals(event.getName())) {
            return;
        }
        event.deferReply(true).queue();

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(config.get("discord.panel.title"))
                .setDescription(config.get("discord.panel.description"))
                .setColor(0x5865F2)
                .setFooter(FOOTER)
                .build();

        event.getChannel().sendMessageEmbeds(embed)
                .setComponents(ActionRow.of(Button.success(BUTTON_ID, config.get("discord.panel.button"))))
                .queue(
                        message -> event.getHook().editOriginal(config.get("discord.panel.sent")).queue(),
                        err -> event.getHook().editOriginal(config.get("discord.panel.failed")).queue()
                );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!BUTTON_ID.equals(event.getComponentId())) {
            return;
        }
        long discordId = event.getUser().getIdLong();

        Optional<LinkManager.Linked> linked = links.getByDiscord(discordId);
        if (linked.isPresent()) {
            event.reply(config.format("discord.already-linked", "player", linked.get().name()))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        LinkManager.Code code = links.createCode(discordId);
        String text = config.format("discord.code",
                "code", code.value(),
                "expires", "<t:" + (code.expiresAt() / 1000L) + ":R>");

        // Ephemeral = only the person who clicked can see the code
        event.reply(text).setEphemeral(true).queue();
    }
}
