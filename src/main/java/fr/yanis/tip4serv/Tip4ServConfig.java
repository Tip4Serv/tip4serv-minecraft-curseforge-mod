package fr.yanis.tip4serv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Tip4ServConfig {
    private static final String CONFIG_FILE = "tip4serv/config.json";

    private static int interval = 1;
    private static String storeMessage = "Link to the store: {storeLink}";
    private static String storeLink = "https://tip4serv.com";
    private static String messageSuccess = "§a[Tip4Serv] §7You have just received your purchase, thank you";

    private static class ConfigData {
        int interval;
        String storeMessage;
        String storeLink;
        String messageSuccess;
    }

    public static void initConfig() {
        try {
            Path configPath = Paths.get(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.createFile(configPath);
            }
            loadConfig();
        } catch (IOException ignored) {
        }
    }

    public static void loadConfig() {
        Path path = Paths.get(CONFIG_FILE);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                saveConfig();
                return;
            }
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(content, ConfigData.class);
            if (data != null) {
                interval = data.interval;
                storeMessage = data.storeMessage;
                storeLink = data.storeLink;
                messageSuccess = data.messageSuccess;
            }
        } catch (IOException ignored) {
        }
    }

    public static void saveConfig() {
        ConfigData data = new ConfigData();
        data.interval = interval;
        data.storeMessage = storeMessage;
        data.storeLink = storeLink;
        data.messageSuccess = messageSuccess;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(data);
        try {
            Files.writeString(Paths.get(CONFIG_FILE), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            loadConfig();
        } catch (IOException ignored) {
        }
    }

    public static int getInterval() {
        try {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), ConfigData.class);

            return data.interval;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 1;
    }

    public static String getStoreMessage() {
        try {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), ConfigData.class);

            return data.storeMessage;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "Link to the store: {storeLink}";
    }

    public static String getStoreLink() {
        try {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), ConfigData.class);

            return data.storeLink;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "https://tip4serv.com";
    }

    public static String getMessageSuccess() {
        try {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), ConfigData.class);

            return data.messageSuccess;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "§a[Tip4Serv] §7You have just received your purchase, thank you";
    }
}
