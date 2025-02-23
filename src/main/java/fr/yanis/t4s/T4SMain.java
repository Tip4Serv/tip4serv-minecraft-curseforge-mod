package fr.yanis.t4s;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
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

public class T4SMain implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(T4SMain.class);
    private static T4SMain INSTANCE;
    private static MinecraftServer serverInstance = null;

    // Pour le HMAC
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";

    // Réponse
    public static String lastResponse = "";
    private static final String API_URL = "https://api.tip4serv.com/payments_api_v2.php";
    private static final String RESPONSE_FILE_PATH = "tip4serv/response.json";

    @Override
    public void onInitialize() {
        LOGGER.info("[Tip4Serv] Initializing mod (Fabric 1.18.2).");
        INSTANCE = this;

        // On initie la config + la clé
        Tip4ServConfig.initConfig();
        Tip4ServKey.init();

        // Enregistrement des commandes (Fabric)
        CommandEvents.registerCommands();

        // On écoute l’événement de démarrage du serveur
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            Tip4ServKey.loadKey()
                    .thenRun(() -> launchRequest(true))
                    .exceptionally(e -> {
                        LOGGER.error(e.getMessage());
                        return null;
                    });

            // On planifie la vérification régulière
            ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
            executor.scheduleAtFixedRate(
                    () -> launchRequest(false),
                    Tip4ServConfig.getInterval(),
                    Tip4ServConfig.getInterval(),
                    TimeUnit.MINUTES
            );
        });
    }

    public void launchRequest(boolean log) {
        if (serverInstance == null) {
            return;
        }
        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(() -> {
            try {
                // On vérifie que l’API key est correcte
                if (!Tip4ServKey.getApiKey().contains(".")) {
                    if (log) {
                        LOGGER.warn("[Tip4Serv] Please provide a correct apiKey in tip4serv/tip4serv.key");
                    }
                    return;
                }

                String json_string = sendHttpRequest("yes");

                // Cas particuliers renvoyés par l’API
                if (json_string.contains("[Tip4serv info] No pending payments found")) {
                    if (log) LOGGER.info("[Tip4Serv] No pending payments found.");
                    return;
                } else if (json_string.contains("[Tip4serv error]")) {
                    if (log) LOGGER.warn("[Tip4Serv] Error while checking payments: " + json_string);
                    return;
                } else if (json_string.contains("[Tip4serv info]")) {
                    if (log) LOGGER.info("[Tip4Serv] API info response received: " + json_string);
                    return;
                }

                // On parse le JSON retourné
                JsonArray infosArr = JsonParser.parseString(json_string).getAsJsonArray();
                JsonObject new_json = new JsonObject();
                boolean update_now = false;

                for (int i1 = 0; i1 < infosArr.size(); i1++) {
                    JsonObject infos_obj = infosArr.get(i1).getAsJsonObject();
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

                    // Vérifie si le joueur est en ligne
                    String player_connected = checkOnlinePlayer(uuidStr, player_str);
                    if (player_connected != null) {
                        player_str = player_connected;
                    }

                    boolean redo_cmd = false;

                    for (int i2 = 0; i2 < cmds.size(); i2++) {
                        JsonElement elem = cmds.get(i2);
                        if (!elem.isJsonObject()) {
                            continue;
                        }
                        JsonObject cmds_obj = elem.getAsJsonObject();
                        String state = safeGetAsString(cmds_obj, "state");
                        String cmdId = safeGetAsString(cmds_obj, "id");
                        String cmdStr = safeGetAsString(cmds_obj, "str")
                                .replace("{minecraft_username}", player_str);

                        if ("1".equals(state)) {
                            // Commande à exécuter seulement si le joueur est en ligne
                            if (player_connected == null) {
                                // Joueur pas en ligne => on réessayera plus tard
                                redo_cmd = true;
                            } else {
                                // On exécute la commande côté serveur
                                // Deuxième extrait
                                serverInstance.execute(() -> {
                                    ServerCommandSource source = new ServerCommandSource(
                                            CommandOutput.DUMMY,
                                            new Vec3d(0, 0, 0),
                                            new Vec2f(0, 0),
                                            serverInstance.getOverworld(),
                                            4,
                                            "Tip4Serv",
                                            new LiteralText("Tip4Serv"),
                                            serverInstance,
                                            null
                                    );
                                    try {
                                        serverInstance.getCommandManager().getDispatcher().execute(cmdStr, source);
                                    } catch (CommandSyntaxException e) {
                                        throw new RuntimeException(e);
                                    }
                                });

                                new_cmds.addProperty(cmdId, 3);
                                update_now = true;
                            }
                        } else if ("0".equals(state)) {
                            // Commande à exécuter sans condition
                            // Premier extrait
                            serverInstance.execute(() -> {
                                ServerCommandSource source = new ServerCommandSource(
                                        CommandOutput.DUMMY,
                                        new Vec3d(0, 0, 0),            // Position
                                        new Vec2f(0, 0),               // Rotation
                                        serverInstance.getOverworld(), // Dimension
                                        4,                             // Niveau de permission (4 = OP)
                                        "Tip4Serv",                    // Nom interne
                                        new LiteralText("Tip4Serv"),   // Nom affiché si le cmd source parle dans le chat
                                        serverInstance,
                                        null
                                );
                                // On exécute la commande
                                try {
                                    serverInstance.getCommandManager().getDispatcher().execute(cmdStr, source);
                                } catch (CommandSyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            });

                            new_cmds.addProperty(cmdId, 3);
                            update_now = true;
                        } else {
                            // Commande en erreur
                            new_cmds.addProperty(cmdId, 14);
                            redo_cmd = true;
                        }
                    }

                    new_obj.add("cmds", new_cmds);

                    if (redo_cmd) {
                        new_obj.addProperty("status", 14);
                    } else {
                        new_obj.addProperty("status", 3);
                        // Envoi d’un message au joueur si tout est OK
                        ServerPlayerEntity player = getPlayer(player_str);
                        if (player != null) {
                            player.sendSystemMessage(Text.of(Tip4ServConfig.getMessageSuccess()), player.getUuid());
                        }
                    }
                    new_json.add(id, new_obj);
                }

                lastResponse = new_json.toString();
                boolean finalUpdateNow = update_now;
                writeResponseFileAsync(lastResponse).thenRun(() -> {
                    if (finalUpdateNow) {
                        sendResponse();
                    }
                });
            } catch (Exception e) {
                if (log) {
                    LOGGER.error("[Tip4Serv] Error while checking payments: {}", e.getMessage());
                }
            }
        });
        service.shutdown();
    }

    // Récupère proprement une chaîne JSON
    private static String safeGetAsString(JsonObject obj, String key) {
        JsonElement elem = obj.get(key);
        if (elem != null && elem.isJsonPrimitive()) {
            return elem.getAsString();
        } else {
            return "";
        }
    }

    // Vérifie si un joueur (via UUID ou pseudo) est en ligne
    public static String checkOnlinePlayer(String uuidStr, String mcUsername) {
        if (serverInstance == null) return null;

        uuidStr = uuidStr.replace("\"", "").trim();
        mcUsername = mcUsername.replace("\"", "").trim();

        CompletableFuture<String> future = new CompletableFuture<>();
        String finalUuidStr = uuidStr;
        String finalMcUsername = mcUsername;
        serverInstance.execute(() -> {
            for (ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
                String currentName = player.getGameProfile().getName();
                String currentUuidNoDash = player.getUuid().toString().replace("-", "");

                // Si l’API renvoie "name" ou rien, on compare le pseudo
                if (finalUuidStr.equalsIgnoreCase("name") || finalUuidStr.isEmpty()) {
                    if (currentName.equalsIgnoreCase(finalMcUsername)) {
                        future.complete(currentName);
                        return;
                    }
                } else {
                    // Sinon on compare l’UUID
                    if (currentUuidNoDash.equalsIgnoreCase(finalUuidStr)) {
                        future.complete(currentName);
                        return;
                    }
                }
            }
            future.complete(null);
        });

        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    // Récupère l’instance du joueur en ligne
    public static ServerPlayerEntity getPlayer(String mcUsername) {
        if (serverInstance == null) return null;
        for (ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
            if (player.getGameProfile().getName().equals(mcUsername)) {
                return player;
            }
        }
        return null;
    }

    // Calcul de la signature HMAC
    public static String calculateHMAC(String server_id, String public_key, String private_key, long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(private_key.getBytes(), HMAC_SHA1_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
            String data = server_id + public_key + timestamp;
            byte[] rawHmac = mac.doFinal(data.getBytes());
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // Envoi d’une réponse (retours de commandes exécutées) à l’API
    public static void sendResponse() {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty()) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis();
            URL url = new URL(API_URL);
            String macSignature = calculateHMAC(
                    Tip4ServKey.getServerID(),
                    Tip4ServKey.getPublicKey(),
                    Tip4ServKey.getPrivateKey(),
                    timestamp
            );
            String fileContent = readResponseFile();
            String jsonEncoded = URLEncoder.encode(fileContent.isEmpty() ? "{}" : fileContent, StandardCharsets.UTF_8);

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.addRequestProperty("Authorization", macSignature);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            try (var outputStream = connection.getOutputStream()) {
                outputStream.write(jsonEncoded.getBytes());
                outputStream.flush();
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            // On appelle ensuite "update"
            sendHttpRequest("update");
        } catch (Exception ignored) {
        }
    }

    // Envoi de la requête HTTP (GET) vers l’API
    public static String sendHttpRequest(String cmd) {
        if (Tip4ServKey.getApiKey().isEmpty()
                || Tip4ServKey.getServerID().isEmpty()
                || Tip4ServKey.getPrivateKey().isEmpty()) {
            return "false";
        }
        try {
            long timestamp = System.currentTimeMillis();
            String fileContent = readResponseFile();
            String jsonEncoded = URLEncoder.encode(fileContent.isEmpty() ? "{}" : fileContent, StandardCharsets.UTF_8);
            String macSignature = calculateHMAC(
                    Tip4ServKey.getServerID(),
                    Tip4ServKey.getPublicKey(),
                    Tip4ServKey.getPrivateKey(),
                    timestamp
            );
            String urlString = API_URL
                    + "?id=" + Tip4ServKey.getServerID()
                    + "&time=" + timestamp
                    + "&json=" + jsonEncoded
                    + "&get_cmd=" + cmd;

            URL url = new URL(urlString);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.addRequestProperty("User-Agent", "Mozilla/5.0");
            connection.addRequestProperty("Accept", "application/json");
            connection.addRequestProperty("Authorization", macSignature);
            connection.setRequestProperty("Content-Type", "application/json");

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            if ("update".equals(cmd)) {
                clearResponseFile();
            }
            return response.toString();
        } catch (Exception e) {
            return "false";
        }
    }

    // Vérification de la connexion (commande /tip4serv connect)
    public static void checkConnection(Entity entity) {
        if (Tip4ServKey.getApiKey().isEmpty()
                || Tip4ServKey.getServerID().isEmpty()
                || Tip4ServKey.getPrivateKey().isEmpty()
                || Tip4ServKey.getPublicKey().isEmpty()) {
            if (entity == null) {
                LOGGER.warn("[Tip4Serv] Please provide a correct apiKey in tip4serv/tip4serv.key file");
            } else {
                entity.sendSystemMessage(
                        Text.of("Please provide a correct apiKey in tip4serv/tip4serv.key file"),
                        entity.getUuid()
                );
            }
            return;
        }

        String response = sendHttpRequest("no");
        if (entity == null) {
            if (response.contains("[Tip4serv error]")) {
                LOGGER.error("[Tip4Serv] Error while connecting to Tip4Serv API: " + response);
            } else {
                LOGGER.info("[Tip4Serv] Connection to Tip4Serv API: " + response);
            }
        } else {
            if (response.contains("[Tip4serv error]")) {
                entity.sendSystemMessage(Text.of("Error while connecting to Tip4Serv API: " + response), entity.getUuid());
            } else {
                entity.sendSystemMessage(Text.of("Connection to Tip4Serv API: " + response), entity.getUuid());
            }
        }
    }

    // Écriture et lecture du fichier response.json
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
            return "";
        }
    }

    // Getter de l’instance
    public static T4SMain getINSTANCE() {
        return INSTANCE;
    }
}