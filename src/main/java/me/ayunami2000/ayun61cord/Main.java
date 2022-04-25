package me.ayunami2000.ayun61cord;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.message.embed.EmbedBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements CommandExecutor, Listener {
    public static Main plugin;
    private DiscordApi discordApi = null;
    private ServerTextChannel chat = null;
    private ServerTextChannel console = null;
    private int sendChatTask = -1;
    private int sendLogTask = -1;
    private boolean sendLogs = false;

    @Override
    public void onLoad() {
        plugin = this;
    }

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        MessageHandler.initMessages();
        try {
            discordApi = new DiscordApiBuilder().setToken(this.getConfig().getString("token")).login().get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return;
        }
        if (this.getConfig().getBoolean("chat.enabled")) {
            chat = discordApi.getServerTextChannelById(this.getConfig().getString("chat.id")).orElse(null);
            if (chat == null) return;
        }
        if (this.getConfig().getBoolean("console.enabled")) {
            console = discordApi.getServerTextChannelById(this.getConfig().getString("console.id")).orElse(null);
            if (console == null) return;
        }
        if (chat != null) {
            chat.sendMessage(MessageHandler.getMessage("started"));
            String cmdPrefix = this.getConfig().getBoolean("chat.commands.enabled") ? this.getConfig().getString("chat.commands.prefix").toLowerCase() : null;
            boolean useNick = this.getConfig().getBoolean("chat.nick");
            chat.addMessageCreateListener(messageCreateEvent -> {
                MessageAuthor messageAuthor = messageCreateEvent.getMessageAuthor();
                if (messageAuthor.isYourself()) return;
                String messageContent = messageCreateEvent.getMessageContent();
                Message message = messageCreateEvent.getMessage();
                String messageContentLower = messageContent.toLowerCase();
                boolean wasCommand = false;
                if (cmdPrefix != null && messageContentLower.startsWith(cmdPrefix)) {
                    wasCommand = true;
                    switch (messageContentLower.substring(cmdPrefix.length())) {
                        case "l":
                        case "list":
                        case "lsit":
                        case "online":
                        case "who":
                            Player[] players = this.getServer().getOnlinePlayers();
                            StringBuilder playerListStrBldr = new StringBuilder();
                            for (Player player : players) {
                                playerListStrBldr.append(player.getName()).append(", ");
                            }
                            if (playerListStrBldr.length() == 0) {
                                playerListStrBldr.append(MessageHandler.getMessage("noPlayers"));
                            } else {
                                playerListStrBldr.setLength(playerListStrBldr.length() - 2);
                            }
                            message.reply(new EmbedBuilder().setTitle(MessageHandler.getMessage("listTitle", players.length)).setDescription(playerListStrBldr.toString()));
                            break;
                        default:
                            wasCommand = false;
                    }
                }
                if (wasCommand) return;
                StringBuilder inGameMsg = new StringBuilder(messageContent);
                for (MessageAttachment attachment : messageCreateEvent.getMessageAttachments()) {
                    inGameMsg.append(MessageHandler.getMessage("attachment", attachment.getUrl()));
                }
                this.getServer().broadcastMessage(MessageHandler.getMessage("inGame", useNick ? messageAuthor.getDisplayName() : messageAuthor.getDiscriminatedName(), inGameMsg.toString()));
            });
            sendChatTask = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, this::sendChatQueue, 0, 20);
        }
        if (console != null) {
            sendLogs = this.getConfig().getBoolean("console.logs");
            if (sendLogs) {
                List<String> logQueue = new ArrayList<>();
                this.getLogger().addHandler(new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        if (record.getLevel().intValue() >= Level.INFO.intValue()) {
                            logQueue.add(record.getMessage());
                        }
                    }

                    @Override
                    public void flush() { }

                    @Override
                    public void close() throws SecurityException { }
                });
                sendLogTask = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
                    if (logQueue.size() > 0) {
                        String fullMsg = String.join("\n", logQueue);
                        if (fullMsg.length() > 1990) fullMsg = fullMsg.substring(0, 1990) + "...";
                        fullMsg = fullMsg.replace("```", "``\\`");
                        console.sendMessage("```" + fullMsg + "\n```");
                        logQueue.clear();
                    }
                }, 0, 20);
            }
            console.addMessageCreateListener(messageCreateEvent -> {
                if (messageCreateEvent.getMessageAuthor().isYourself()) return;
                String[] msgLines = messageCreateEvent.getMessageContent().split("\n");
                this.getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
                    for (String cmd : msgLines) this.getServer().dispatchCommand(this.getServer().getConsoleSender(), cmd);
                });
                if (!sendLogs) messageCreateEvent.deleteMessage();
            });
        }
    }

    @Override
    public void onDisable() {
        if (chat != null) {
            chat.sendMessage(MessageHandler.getMessage("stopped"));
        }
        if (sendChatTask != -1) {
            this.getServer().getScheduler().cancelTask(sendChatTask);
            sendChatTask = -1;
        }
        if (sendLogTask != -1) {
            this.getServer().getScheduler().cancelTask(sendLogTask);
            sendLogTask = -1;
        }
        if (discordApi != null) {
            discordApi.disconnect();
            discordApi = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0){
            MessageHandler.sendMessage(sender, "command");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            MessageHandler.sendMessage(sender, "reload");
            reloadConfig();
            onDisable();
            onEnable();
        }
        return true;
    }

    private String filterMsg(String in) {
        //from https://github.com/Swiiz/discord-escape/blob/master/index.js
        return in.replaceAll("(\\_|\\*|\\~|\\`|\\||\\\\|\\<|\\>|\\:|\\!)", "\\\\$1").replaceAll("@(everyone|here|[!&]?[0-9]{17,21})", "@\u200b\\\\$1");
    }

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)&[0-9A-FK-ORX]");

    private List<String> msgQueue = new ArrayList<>();

    public void sendChat(String username, String msg) {
        if (chat == null) return;
        msg = STRIP_COLOR_PATTERN.matcher(msg).replaceAll("");
        msgQueue.add(filterMsg(MessageHandler.getMessage("inCord", username, msg)));
    }

    private void sendChatQueue() {
        if (msgQueue.size() > 0) {
            String fullMsg = String.join("\n", msgQueue);
            if (fullMsg.length() > 1997) fullMsg = fullMsg.substring(0, 1997) + "...";
            chat.sendMessage(fullMsg);
            msgQueue.clear();
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (chat != null) {
            sendChat(event.getPlayer().getName(), event.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (chat != null) {
            msgQueue.add(MessageHandler.getMessage("joined", event.getPlayer().getName()));
        }
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        if (chat != null) {
            msgQueue.add(MessageHandler.getMessage("left", event.getPlayer().getName()));
        }
    }
}