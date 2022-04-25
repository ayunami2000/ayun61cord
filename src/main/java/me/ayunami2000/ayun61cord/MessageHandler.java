package me.ayunami2000.ayun61cord;

import org.apache.commons.lang.StringUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.HashMap;

public class MessageHandler {
    public static HashMap<String, String> messageData = new HashMap<>();

    public static void initMessages() {
        File directory = Main.plugin.getDataFolder();
        if (! directory.exists()){
            directory.mkdir();
        }

        File f = new File(directory + File.separator + "messages.yml");
        if (!f.exists()) {
            try {
                InputStream initialStream = Main.plugin.getResource("messages.yml");
                Files.copy(initialStream, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                initialStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(f);
        for (String message : config.getConfigurationSection("").getKeys(false)) {
            if (config.isString(message)) {
                messageData.put(message, config.getString(message).replaceAll("&", "§"));
            }else if(config.isList(message)){
                messageData.put(message, StringUtils.join(config.getStringList(message), "\n").replaceAll("&", "§"));
            }
        }
    }

    public static String getMessage(String key, Object... args){
        return MessageFormat.format(messageData.getOrDefault(key, key), args);
    }

    public static void sendMessage(CommandSender commandSender, String key, Object... args){
        commandSender.sendMessage(getMessage(key, args));
    }
}