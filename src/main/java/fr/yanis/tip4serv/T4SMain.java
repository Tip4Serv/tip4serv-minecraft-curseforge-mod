package fr.yanis.tip4serv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Mod("tip4serv")
public class T4SMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(T4SMain.class);
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";

    private static String apiKey = "";
    private static String serverId = "";
    private static String publicKey = "";
    private static String privateKey = "";
    public static int interval = 1;

    public static String lastResponse = "";
    private static final String API_URL = "https://api.tip4serv.com/payments_api_v2.php";
    private static final String RESPONSE_FILE_PATH = "tip4serv/response.json";

    public T4SMain() {
        LOGGER.info("Initialisation de l'instance du mod Tip4Serv.");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);

        Tip4ServConfig.register();
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Tip4Serv mod est en cours d'initialisation...");
        loadConfig();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        LOGGER.info("Planification de la vérification de l'API toutes les 10 secondes.");
        executor.scheduleAtFixedRate(() -> {
            LOGGER.debug("Démarrage de la tâche planifiée d'appel à l'API.");
            if (ServerLifecycleHooks.getCurrentServer() == null) {
                LOGGER.warn("Le serveur n'est pas disponible pour vérifier les joueurs en ligne.");
                return;
            }
            ExecutorService service = Executors.newSingleThreadExecutor();
            service.execute(() -> {
                try {
                    if (!apiKey.contains(".")) {
                        LOGGER.warn("La clé API ne contient pas '.', requête API ignorée.");
                        return;
                    }
                    LOGGER.debug("Envoi d'une requête HTTP à l'API avec le paramètre 'yes'.");
                    String json_string = sendHttpRequest("yes");
                    LOGGER.debug("Réponse reçue de l'API : {}", json_string);

                    if (json_string.contains("[Tip4serv info] No pending payments found")) {
                        LOGGER.info("Aucun paiement en attente.");
                        return;
                    } else if (json_string.contains("[Tip4serv error]")) {
                        LOGGER.error("L'API a retourné une erreur : {}", json_string);
                        return;
                    } else if (json_string.contains("[Tip4serv info]")) {
                        LOGGER.info("Réponse info de l'API reçue : {}", json_string);
                        return;
                    }

                    JsonArray infosArr = JsonParser.parseString(json_string).getAsJsonArray();
                    LOGGER.debug("JSON parsé avec succès, nombre d'éléments : {}", infosArr.size());
                    JsonObject new_json = new JsonObject();
                    boolean update_now = false;

                    for (int i1 = 0; i1 < infosArr.size(); i1++) {
                        JsonObject infos_obj = infosArr.get(i1).getAsJsonObject();
                        // Extraction sécurisée des valeurs
                        String id = safeGetAsString(infos_obj, "id");
                        String action = safeGetAsString(infos_obj, "action");
                        String player_str = safeGetAsString(infos_obj, "player");
                        String uuidStr = safeGetAsString(infos_obj, "uuid");
                        JsonArray cmds = infos_obj.get("cmds").getAsJsonArray();
                        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

                        JsonObject new_obj = new JsonObject();
                        new_obj.addProperty("date", date);
                        new_obj.addProperty("action", action);
                        JsonObject new_cmds = new JsonObject();

                        LOGGER.debug("Vérification du statut en ligne pour le joueur '{}' (UUID: {}).", player_str, uuidStr);
                        String player_connected = check_online_player(uuidStr, player_str);
                        if (player_connected != null) {
                            LOGGER.debug("Le joueur {} est en ligne.", player_connected);
                            player_str = player_connected;
                        } else {
                            LOGGER.debug("Le joueur {} n'est pas en ligne.", player_str);
                        }

                        LOGGER.info("Traitement du paiement pour le joueur : {}", player_str);
                        List<String> cmds_failed = new ArrayList<>();
                        boolean redo_cmd = false;

                        for (int i2 = 0; i2 < cmds.size(); i2++) {
                            JsonElement elem = cmds.get(i2);
                            if (!elem.isJsonObject()) {
                                LOGGER.warn("L'élément cmds à l'index {} n'est pas un objet JSON, il est ignoré.", i2);
                                continue;
                            }
                            JsonObject cmds_obj = elem.getAsJsonObject();
                            String state = safeGetAsString(cmds_obj, "state");
                            String cmdId = safeGetAsString(cmds_obj, "id");
                            String cmdStr = safeGetAsString(cmds_obj, "str").replace("{minecraft_username}", player_str);

                            LOGGER.debug("Traitement de la commande id {} avec l'état {}.", cmdId, state);
                            // Pour state "1", le joueur doit être en ligne
                            if (state.equals("1")) {
                                if (player_connected == null) {
                                    LOGGER.warn("Commande {} non exécutée car le joueur {} n'est pas en ligne (requiert présence).", cmdId, player_str);
                                    //new_cmds.addProperty(cmdId, 14);
                                    cmds_failed.add(cmdId);
                                    redo_cmd = true;
                                } else {
                                    LOGGER.info("Exécution de la commande '{}' pour le joueur {} (vérification de présence en ligne).", cmdStr, player_str);
                                    ServerLifecycleHooks.getCurrentServer().execute(() -> {
                                        CommandSourceStack source = ServerLifecycleHooks.getCurrentServer().createCommandSourceStack();
                                        ServerLifecycleHooks.getCurrentServer().getCommands().performCommand(source, cmdStr);
                                        LOGGER.debug("Commande '{}' exécutée.", cmdStr);
                                    });
                                    new_cmds.addProperty(cmdId, 3);
                                    update_now = true;
                                }
                            }
                            // Pour state "0", exécution sans vérification de présence
                            else if (state.equals("0")) {
                                LOGGER.info("Exécution de la commande '{}' pour le joueur {} (aucune vérification en ligne requise).", cmdStr, player_str);
                                ServerLifecycleHooks.getCurrentServer().execute(() -> {
                                    CommandSourceStack source = ServerLifecycleHooks.getCurrentServer().createCommandSourceStack();
                                    ServerLifecycleHooks.getCurrentServer().getCommands().performCommand(source, cmdStr);
                                    LOGGER.debug("Commande '{}' exécutée.", cmdStr);
                                });
                                new_cmds.addProperty(cmdId, 3);
                                update_now = true;
                            } else {
                                LOGGER.warn("État inconnu '{}' pour la commande {}. Commande ignorée.", state, cmdId);
                                new_cmds.addProperty(cmdId, 14);
                                cmds_failed.add(cmdId);
                                redo_cmd = true;
                            }
                        }
                        new_obj.add("cmds", new_cmds);

                        if (redo_cmd) {
                            new_obj.addProperty("status", 14);
                        } else {
                            new_obj.addProperty("status", 3);
                            ServerPlayer player = getPlayer(player_str);
                            if (player != null) {
                                player.sendMessage(new TextComponent(Tip4ServConfig.getMessageSuccess().get()), player.getUUID());
                                LOGGER.info("Message de succès envoyé au joueur {}.", player_str);
                            } else {
                                LOGGER.warn("Le joueur {} est introuvable lors de l'envoi du message de succès.", player_str);
                            }
                        }
                        new_json.add(id, new_obj);
                    }

                    lastResponse = new_json.toString();
                    boolean finalUpdate_now = update_now;
                    writeResponseFileAsync(lastResponse).thenRun(() -> {
                        LOGGER.debug("JSON de réponse écrit dans le fichier : {}", lastResponse);
                        if (finalUpdate_now) {
                            LOGGER.debug("Un ou plusieurs paiements ont été traités, envoi de la réponse à l'API.");
                            sendResponse();
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Erreur lors de la vérification des paiements", e);
                }
            });
            service.shutdown();
        }, 0, interval, TimeUnit.MINUTES);
    }

    /**
     * Méthode utilitaire pour extraire en toute sécurité une valeur de type chaîne depuis un objet JSON.
     */
    private static String safeGetAsString(JsonObject obj, String key) {
        JsonElement elem = obj.get(key);
        if (elem != null && elem.isJsonPrimitive()) {
            return elem.getAsString();
        } else {
            LOGGER.warn("La clé '{}' n'est pas une primitive dans l'objet : {}", key, obj);
            return "";
        }
    }

    public static String check_online_player(String uuid_str, String mc_username) {
        uuid_str = uuid_str.replace("\"", "").trim();
        mc_username = mc_username.replace("\"", "").trim();

        LOGGER.debug("Vérification de la présence en ligne du joueur avec UUID '{}' ou nom '{}'.", uuid_str, mc_username);

        if (ServerLifecycleHooks.getCurrentServer() == null) {
            LOGGER.warn("Le serveur n'est pas disponible pour vérifier les joueurs en ligne.");
            return null;
        }

        CompletableFuture<String> future = new CompletableFuture<>();

        String finalUuid_str = uuid_str;
        String finalMc_username = mc_username;
        ServerLifecycleHooks.getCurrentServer().execute(() -> {
            for (Player player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                String loopedPlayerUsername = player.getGameProfile().getName();
                UUID playerUUID = player.getUUID();
                if (finalUuid_str.equalsIgnoreCase("name") || finalUuid_str.isEmpty()) {
                    if (loopedPlayerUsername.equalsIgnoreCase(finalMc_username)) {
                        LOGGER.debug("Le joueur {} est en ligne (vérifié par nom).", loopedPlayerUsername);
                        future.complete(loopedPlayerUsername);
                        return;
                    }
                } else {
                    if (playerUUID.toString().replace("-", "").equalsIgnoreCase(finalUuid_str)) {
                        LOGGER.debug("Le joueur {} est en ligne (vérifié par UUID).", loopedPlayerUsername);
                        future.complete(loopedPlayerUsername);
                        return;
                    }
                }
            }
            LOGGER.debug("Le joueur {} n'a pas été trouvé en ligne.", finalMc_username);
            future.complete(null);
        });

        try {
            return future.get();
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la vérification de la présence en ligne du joueur", e);
            return null;
        }
    }



    public static ServerPlayer getPlayer(String mc_username) {
        for (ServerPlayer player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
            String loopedPlayerUsername = player.getGameProfile().getName();
            if (loopedPlayerUsername.equals(mc_username)) {
                return player;
            }
        }
        LOGGER.debug("getPlayer : Le joueur {} est introuvable.", mc_username);
        return null;
    }

    private static void loadConfig() {
        LOGGER.info("Chargement de la configuration du mod Tip4Serv.");
        apiKey = Tip4ServConfig.getApiKey();
        interval = Tip4ServConfig.getInterval();
        if (!apiKey.isEmpty() && apiKey.contains(".")) {
            String[] parts = apiKey.split("\\.");
            if (parts.length == 3) {
                serverId = parts[0];
                privateKey = parts[1];
                publicKey = parts[2];
                LOGGER.info("Configuration chargée avec succès. Server ID: {}", serverId);
                checkConnection(null);
            } else {
                LOGGER.error("La clé API n'est pas au format attendu (3 parties séparées par un point). Clé fournie: {}", apiKey);
            }
        } else {
            LOGGER.error("La clé API n'est pas définie ou est dans un format incorrect.");
        }
    }

    public static String calculateHMAC(String server_id, String public_key, String private_key, Long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(private_key.getBytes(), HMAC_SHA1_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
            String datas = server_id + public_key + timestamp;
            LOGGER.debug("Calcul du HMAC pour la chaîne : {}", datas);
            byte[] rawHmac = mac.doFinal(datas.getBytes());
            String result = Base64.getEncoder().encodeToString(rawHmac);
            LOGGER.debug("HMAC calculé avec succès.");
            return result;
        } catch (GeneralSecurityException e) {
            LOGGER.warn("Tip4Serv error: Erreur inattendue lors de la création du hash.", e);
            throw new IllegalArgumentException(e);
        }
    }

    public static void sendResponse() {
        if (apiKey.isEmpty() || serverId.isEmpty() || privateKey.isEmpty()) {
            LOGGER.warn("La clé API, le Server ID ou la clé privée ne sont pas définis !");
            return;
        }
        try {
            long timestamp = new Date().getTime();
            URL url = new URL(API_URL);
            String macSignature = calculateHMAC(serverId, publicKey, privateKey, timestamp);
            String fileContent = readResponseFile();
            String jsonEncoded = URLEncoder.encode(fileContent.isEmpty() ? "{}" : fileContent, StandardCharsets.UTF_8);

            LOGGER.debug("Envoi de la réponse à l'API. URL: {} | Timestamp: {} | JSON: {}", API_URL, timestamp, fileContent);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.addRequestProperty("Authorization", macSignature);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (var outputStream = connection.getOutputStream()) {
                outputStream.write(jsonEncoded.getBytes());
                outputStream.flush();
                LOGGER.debug("JSON de réponse envoyé avec succès.");
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            sendHttpRequest("update");
            LOGGER.debug("Réponse reçue de l'API après envoi de la réponse : {}", response.toString());
        } catch (Exception e) {
            LOGGER.error("Erreur de connexion à l'API Tip4Serv", e);
        }
    }

    public static String sendHttpRequest(String cmd) {
        if (apiKey.isEmpty() || serverId.isEmpty() || privateKey.isEmpty()) {
            LOGGER.warn("La clé API, le Server ID ou la clé privée ne sont pas définis !");
            return "false";
        }
        try {
            long timestamp = new Date().getTime();
            String fileContent = readResponseFile();
            String jsonEncoded = URLEncoder.encode(fileContent.isEmpty() ? "{}" : fileContent, StandardCharsets.UTF_8);
            String macSignature = calculateHMAC(serverId, publicKey, privateKey, timestamp);
            String urlString = API_URL + "?id=" + serverId + "&time=" + timestamp + "&json=" + jsonEncoded + "&get_cmd=" + cmd;
            LOGGER.debug("Envoi d'une requête HTTP GET à l'URL : {}", urlString);
            URL url = new URL(urlString);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:25.0) Gecko/20100101 Firefox/25.0");
            connection.addRequestProperty("Accept", "application/json");
            connection.addRequestProperty("Authorization", macSignature);
            connection.setRequestProperty("Content-Type", "application/json");
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            LOGGER.debug("Requête HTTP GET complétée. Réponse : {}", response.toString());
            if (cmd.equals("update")) {
                clearResponseFile();
            }
            return response.toString();
        } catch (Exception e) {
            LOGGER.error("Erreur de connexion à l'API Tip4Serv", e);
            return "false";
        }
    }

    public static void checkConnection(Entity entity) {
        LOGGER.debug("Vérification de la connexion à l'API Tip4Serv.");
        String response = sendHttpRequest("no");
        if (entity == null) {
            if (response.contains("[Tip4serv error]")) {
                LOGGER.error("Erreur lors de la connexion à l'API Tip4Serv : {}", response);
            } else {
                LOGGER.info("Connexion réussie à l'API Tip4Serv : {}", response);
            }
        } else {
            if (response.contains("[Tip4serv error]")) {
                entity.sendMessage(new TextComponent("Erreur lors de la connexion à l'API Tip4Serv : " + response), entity.getUUID());
            } else {
                entity.sendMessage(new TextComponent("Connexion réussie à l'API Tip4Serv : " + response), entity.getUUID());
            }
        }
    }

    // ================================
    // Système de gestion du fichier response.json
    // ================================

    private static CompletableFuture<Void> writeResponseFileAsync(String json) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            Files.writeString(Paths.get(RESPONSE_FILE_PATH), json, StandardCharsets.UTF_8);
            future.complete(null);
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }


    private static void clearResponseFile() {
        writeResponseFileAsync("");
    }

    private static String readResponseFile() {
        try {
            File file = new File(RESPONSE_FILE_PATH);
            if (!file.exists()) {
                return "";
            }
            return Files.readString(Paths.get(RESPONSE_FILE_PATH));
        } catch (Exception e) {
            LOGGER.error("Erreur lors de la lecture du fichier response.json", e);
            return "";
        }
    }
}
