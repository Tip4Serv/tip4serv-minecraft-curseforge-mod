package fr.yanis.tip4serv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Tip4ServConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(Tip4ServConfig.class);
    private static final String CONFIG_FILE = "tip4serv/config.json";

    private static String apiKey = "";
    private static int interval = 1;
    private static String storeMessage = "Link to the store: {storeLink}";
    private static String storeLink = "https://tip4serv.com";
    private static String messageSuccess = "§a[Tip4Serv] §7You have just received your purchase, thank you";

    private static class ConfigData {
        String apiKey;
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
        } catch (IOException e) {
            LOGGER.error("Erreur lors de la création du dossier de configuration : {}", CONFIG_FILE, e);
        }
    }

    public static void loadConfig() {
        Path path = Paths.get(CONFIG_FILE);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                LOGGER.warn("Le fichier de configuration est vide, on crée une configuration par défaut.");
                saveConfig();
                return;
            }
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(content, ConfigData.class);
            if (data != null) {
                apiKey = data.apiKey;
                interval = data.interval;
                storeMessage = data.storeMessage;
                storeLink = data.storeLink;
                messageSuccess = data.messageSuccess;

                if (apiKey != null && !apiKey.isEmpty()) {
                    String[] parts = apiKey.split("\\.");
                    if (parts.length == 3) {
                        LOGGER.info("Configuration chargée avec succès. Server ID: {}", Tip4ServConfig.getServerID());
                        T4SMain.checkConnection(null);
                    } else {
                        LOGGER.error("La clé API n'est pas au format attendu (3 parties séparées par un point). Clé fournie: {}", apiKey);
                    }
                }
            } else {
                LOGGER.warn("Le contenu de la configuration est invalide.");
            }
            LOGGER.info("Configuration chargée depuis : {}", path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Erreur lors de la lecture du fichier de configuration", e);
        }
    }

    public static void saveConfig() {
        ConfigData data = new ConfigData();
        data.apiKey = apiKey;
        data.interval = interval;
        data.storeMessage = storeMessage;
        data.storeLink = storeLink;
        data.messageSuccess = messageSuccess;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(data);
        try {
            Files.writeString(Paths.get(CONFIG_FILE), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOGGER.info("Configuration sauvegardée dans : {}", CONFIG_FILE);
            loadConfig();
        } catch (IOException e) {
            LOGGER.error("Erreur lors de l'écriture du fichier de configuration", e);
        }
    }

    public static String getApiKey() {
        try {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(Files.readString(Paths.get(CONFIG_FILE)), ConfigData.class);

            return data.apiKey.trim();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getServerID(){
        return getApiKey().split("\\.")[0];
    }

    public static String getPrivateKey(){
        if (getApiKey().split("\\.").length < 3)
            return "";

        return getApiKey().split("\\.")[1];
    }

    public static String getPublicKey(){
        if (getApiKey().split("\\.").length < 3)
            return "";

        return getApiKey().split("\\.")[2];
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
