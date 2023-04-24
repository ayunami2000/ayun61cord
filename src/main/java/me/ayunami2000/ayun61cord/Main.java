package me.ayunami2000.ayun61cord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.OkHttpClient;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements CommandExecutor, Listener {
	public static Main plugin;
	private JDA jda = null;
	public TextChannel chat = null;
	public TextChannel console = null;
	private int sendChatTask = -1;
	private int sendLogTask = -1;
	private boolean sendLogs = false;
	private boolean hasRegisteredEvents = false;
	private Handler logHandler = null;
	private int filterMode = 0;

	public String cmdPrefix = "!";
	public boolean useNick = false;
	public boolean useUsername = true;
	public List<String> listAliases = null;

	public boolean ready = false;

	@Override
	public void onLoad() {
		plugin = this;
	}

	@Override
	public void onEnable() {
		this.saveDefaultConfig();
		if (!hasRegisteredEvents) {
			hasRegisteredEvents = true;
			this.getServer().getPluginManager().registerEvents(this, this);
		}
		MessageHandler.initMessages();
		fixOldConfigs();
		try {
			String token = System.getenv("AYUN61CORD_TOKEN");
			if (token == null) token = this.getConfig().getString("token");
			JDABuilder discBuilder = JDABuilder.createDefault(token);
			discBuilder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
			String prox = this.getConfig().getString("proxy");
			if (!prox.isEmpty()) {
				String[] proxPieces = prox.split(":",2);
				if (proxPieces.length == 2) {
					try {
						int port = Integer.parseInt(proxPieces[1]);
						OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxPieces[0], port)));
						discBuilder.setHttpClientBuilder(httpBuilder);
						discBuilder.setHttpClient(httpBuilder.build());
					} catch (NumberFormatException ignored) {
					}
				}
			}
			discBuilder.addEventListeners(new Events());
			jda = discBuilder.build();
			jda.awaitReady();
		} catch (InterruptedException | javax.security.auth.login.LoginException e) {
			e.printStackTrace();
			return;
		}
		if (this.getConfig().getBoolean("chat.enabled")) {
			chat = jda.getTextChannelById(this.getConfig().getString("chat.id"));
			if (chat == null) return;
		}
		if (this.getConfig().getBoolean("console.enabled")) {
			console = jda.getTextChannelById(this.getConfig().getString("console.id"));
			if (console == null) return;
		}
		if (chat != null) {
			String msg = MessageHandler.getMessage("started");
			if (!msg.isEmpty()) {
				chat.sendMessage(msg).queue();
			}
			filterMode = this.getConfig().getInt("chat.filter");
			cmdPrefix = this.getConfig().getBoolean("chat.commands.enabled") ? this.getConfig().getString("chat.commands.prefix").toLowerCase() : "!";
			useNick = this.getConfig().getBoolean("chat.nick");
			useUsername = this.getConfig().getBoolean("chat.username");
			listAliases = this.getConfig().getStringList("chat.commands.aliases.list");
			listAliases.replaceAll(String::toLowerCase);
			sendChatTask = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, this::sendChatQueue, 0, 10);
		}
		if (console != null) {
			sendLogs = this.getConfig().getBoolean("console.logs.enabled");
			if (sendLogs) {
				boolean colorLogs = this.getConfig().getBoolean("console.logs.ansi");
				List<String> logQueue = new ArrayList<>();
				logHandler = new Handler() {
					@Override
					public void publish(LogRecord record) {
						if (record.getLevel().intValue() >= Level.INFO.intValue()) {
							String msg = record.getMessage();
							if (colorLogs) {
								logQueue.add(msg.replace("\u001B[m", "\u001B[0m"));
							} else {
								logQueue.add(msg.replaceAll("\u001B\\[[;\\d]*m", ""));
							}
						}
					}

					@Override
					public void flush() { }

					@Override
					public void close() throws SecurityException { }
				};
				this.getServer().getLogger().addHandler(logHandler);
				int msgLenLimit = colorLogs ? 1985 : 1989;
				String ansiText = colorLogs ? "ansi" : "";
				sendLogTask = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
					if (logQueue.size() > 0) {
						String fullMsg = String.join("\n", logQueue);
						if (fullMsg.length() > msgLenLimit) fullMsg = fullMsg.substring(0, msgLenLimit) + "...";
						fullMsg = fullMsg.replace("```", "``\\`");
						console.sendMessage("```" + ansiText + "\n" + fullMsg + "\n```").queue();
						logQueue.clear();
					}
				}, 0, 20);
			}
		}
		ready = true;
	}

	public static class Events extends ListenerAdapter {
		@Override
		public void onMessageReceived(@NotNull MessageReceivedEvent event) {
			if (!plugin.ready) return;
			if (!event.isFromType(ChannelType.TEXT)) return;
			User messageAuthor = event.getMessage().getAuthor();
			if (messageAuthor.getIdLong() == event.getJDA().getSelfUser().getIdLong()) return;
			Message message = event.getMessage();
			String messageContent = message.getContentDisplay();
			if (Main.plugin.chat != null && event.getChannel() == Main.plugin.chat) {
				String messageContentLower = messageContent.toLowerCase();
				boolean wasCommand = false;
				if (plugin.cmdPrefix != null && messageContentLower.startsWith(plugin.cmdPrefix)) {
					wasCommand = true;
					String cmd = messageContentLower.substring(plugin.cmdPrefix.length());
					if (plugin.listAliases.contains(cmd)) {
						Player[] players = plugin.getServer().getOnlinePlayers();
						StringBuilder playerListStrBldr = new StringBuilder();
						for (Player player : players) {
							playerListStrBldr.append(filterMsg(getName(player))).append(", ");
						}
						if (playerListStrBldr.length() == 0) {
							playerListStrBldr.append(MessageHandler.getMessage("noPlayers"));
						} else {
							playerListStrBldr.setLength(playerListStrBldr.length() - 2);
						}
						message.replyEmbeds(new EmbedBuilder().setTitle(MessageHandler.getMessage("listTitle", players.length)).setDescription(playerListStrBldr.toString()).build()).queue();
					} else {
						wasCommand = false;
					}
				}
				if (wasCommand) return;
				StringBuilder inGameMsg = new StringBuilder(messageContent);
				for (Message.Attachment attachment : message.getAttachments()) {
					inGameMsg.append(MessageHandler.getMessage("attachment", attachment.getUrl()));
				}
				String name;
				if (plugin.useNick) {
					Member member = event.getMember();
					if (member == null) {
						name = messageAuthor.getName();
					} else {
						name = event.getMember().getEffectiveName();
					}
				} else {
					name = messageAuthor.getName() + "#" + messageAuthor.getDiscriminator();
				}
				for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
					if (onlinePlayer.hasPermission("ayun61cord.ignorediscord")) continue;
					onlinePlayer.sendMessage(MessageHandler.getMessage("inGame", name, inGameMsg.toString()));
				}
			} else if (Main.plugin.console != null && event.getChannel() == Main.plugin.console) {
				String[] msgLines = messageContent.split("\n");
				plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
					for (String cmd : msgLines) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
				});
				if (!plugin.sendLogs) message.delete().queue();
			}
		}
	}

	@Override
	public void onDisable() {
		ready = false;
		if (chat != null) {
			String msg = MessageHandler.getMessage("stopped");
			if (!msg.isEmpty()) {
				chat.sendMessage(msg).queue();
			}
			filterMode = 0;
		}
		if (logHandler != null) {
			this.getServer().getLogger().removeHandler(logHandler);
			logHandler = null;
		}
		if (sendChatTask != -1) {
			this.getServer().getScheduler().cancelTask(sendChatTask);
			sendChatTask = -1;
		}
		if (sendLogTask != -1) {
			this.getServer().getScheduler().cancelTask(sendLogTask);
			sendLogTask = -1;
		}
		if (jda != null) {
			jda.shutdown();
			jda = null;
		}
	}

	private void fixOldConfigs() {
		boolean changed = false;
		if (this.getConfig().isBoolean("console.logs")) {
			changed = true;
			this.getConfig().set("console.logs.enabled", this.getConfig().getBoolean("console.logs"));
			this.getConfig().set("console.logs.ansi", false);
		}
		if (changed) this.saveConfig();
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length != 0 && args[0].equalsIgnoreCase("reload") && (sender.isOp() || sender.hasPermission("ayun61cord.reload"))) {
			MessageHandler.sendMessage(sender, "reload");
			reloadConfig();
			onDisable();
			onEnable();
			return true;
		}
		MessageHandler.sendMessage(sender, "command");
		return true;
	}

	private static String filterMsg(String in) {
		switch (Main.plugin.filterMode) {
			case 0:
				//from https://github.com/Swiiz/discord-escape/blob/master/index.js
				return in.replaceAll("(\\_|\\*|\\~|\\`|\\||\\\\|\\<|\\>|\\:|\\!)", "\\\\$1").replaceAll("@(everyone|here|[!&]?[0-9]{17,21})", "@\u200b\\\\$1");
			case 1:
				return in.replace("```", "``\\`");
			default:
				return in;
		}
	}

	private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)&[0-9A-FK-ORX]");

	private final List<String> msgQueue = new ArrayList<>();

	public void sendChat(String username, String msg) {
		if (chat == null) return;
		msg = STRIP_COLOR_PATTERN.matcher(msg).replaceAll("");
		msgQueue.add(MessageHandler.getMessage("inCord", filterMsg(username), filterMsg(msg)));
	}

	private void sendChatQueue() {
		if (msgQueue.size() > 0) {
			String fullMsg = String.join("\n", msgQueue);
			if (fullMsg.length() > 1997) fullMsg = fullMsg.substring(0, 1997) + "...";
			chat.sendMessage(fullMsg).queue();
			msgQueue.clear();
		}
	}

	private static String getName(Player player) {
		return Main.plugin.useUsername ? player.getName() : ChatColor.stripColor(player.getDisplayName());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onChat(AsyncPlayerChatEvent event) {
		if (event.isCancelled()) return;
		if (chat != null) {
			sendChat(getName(event.getPlayer()), ChatColor.stripColor(event.getMessage()));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onJoin(PlayerJoinEvent event) {
		if (chat != null) {
			String msg = MessageHandler.getMessage("joined", getName(event.getPlayer()));
			if (!msg.isEmpty()) {
				msgQueue.add(msg);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onLeave(PlayerQuitEvent event) {
		if (chat != null) {
			String msg = MessageHandler.getMessage("left", getName(event.getPlayer()));
			if (!msg.isEmpty()) {
				msgQueue.add(msg);
			}
		}
	}
}