package fr.yanis.tip4serv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LogicalSidedProvider;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
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

@Mod("t4s")
public class T4SMain {

    public static MinecraftServer serverInstance;

    private static final Logger LOGGER = LoggerFactory.getLogger(T4SMain.class);
    private static T4SMain INSTANCE = null;
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";

    public static String lastResponse = "";
    private static final String API_URL = "https://api.tip4serv.com/payments_api_v2.php";
    private static final String RESPONSE_FILE_PATH = "tip4serv/response.json";

    public T4SMain() {
        LOGGER.info("Initializing Tip4Serv mod instance.");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);

        Tip4ServConfig.initConfig();
        Tip4ServKey.init();

        INSTANCE = this;
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Tip4Serv mod init.");
    }

    @OnlyIn(Dist.DEDICATED_SERVER)
    @SubscribeEvent
    public void onStart(ServerStartedEvent event) {
        Tip4ServKey.loadKey().thenRun(() -> launchRequest(true)).exceptionally(e -> {
            LOGGER.error(e.getMessage());
            return null;
        });
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> launchRequest(false), Tip4ServConfig.getInterval(), Tip4ServConfig.getInterval(), TimeUnit.MINUTES);

        serverInstance = event.getServer();
    }

    public void launchRequest(boolean log) {
        if (serverInstance == null) {
            return;
        }
        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(() -> {
            try {
                if (!Tip4ServKey.getApiKey().contains(".")) {
                    if (log)
                        LOGGER.warn("Please provide a correct apiKey in tip4serv/tip4serv.key file");
                    return;
                }
                String json_string = sendHttpRequest("yes");

                if (json_string.contains("[Tip4serv info] No pending payments found")) {
                    if (log)
                        LOGGER.info("No pending payments found.");
                    return;
                } else if (json_string.contains("[Tip4serv error]")) {
                    if (log)
                        LOGGER.warn("Error while checking payments: " + json_string);
                    return;
                } else if (json_string.contains("[Tip4serv info]")) {
                    if (log)
                        LOGGER.info("API info response received: " + json_string);
                    return;
                }

                JsonArray infosArr = JsonParser.parseString(json_string).getAsJsonArray();
                JsonObject new_json = new JsonObject();
                boolean update_now = false;

                for (int i1 = 0; i1 < infosArr.size(); i1++) {
                    JsonObject infos_obj = infosArr.get(i1).getAsJsonObject();
                    // Safely extract values from the JSON object
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

                    String player_connected = check_online_player(uuidStr, player_str);
                    if (player_connected != null) {
                        player_str = player_connected;
                    }

                    List<String> cmds_failed = new ArrayList<>();
                    boolean redo_cmd = false;

                    for (int i2 = 0; i2 < cmds.size(); i2++) {
                        JsonElement elem = cmds.get(i2);
                        if (!elem.isJsonObject()) {
                            continue;
                        }
                        JsonObject cmds_obj = elem.getAsJsonObject();
                        String state = safeGetAsString(cmds_obj, "state");
                        String cmdId = safeGetAsString(cmds_obj, "id");
                        String cmdStr = safeGetAsString(cmds_obj, "str").replace("{minecraft_username}", player_str);

                        if (state.equals("1")) {
                            if (player_connected == null) {
                                cmds_failed.add(cmdId);
                                redo_cmd = true;
                            } else {
                                serverInstance.execute(() -> {
                                    CommandSourceStack source = serverInstance.createCommandSourceStack();
                                    try {
                                        serverInstance.getCommands().getDispatcher().execute(cmdStr, source);
                                    } catch (CommandSyntaxException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                                new_cmds.addProperty(cmdId, 3);
                                update_now = true;
                            }
                        }
                        else if (state.equals("0")) {
                            serverInstance.execute(() -> {
                                CommandSourceStack source = serverInstance.createCommandSourceStack();
                                try {
                                    serverInstance.getCommands().getDispatcher().execute(cmdStr, source);
                                } catch (CommandSyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                            new_cmds.addProperty(cmdId, 3);
                            update_now = true;
                        } else {
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
                            player.sendSystemMessage(Component.literal(Tip4ServConfig.getMessageSuccess()));
                        }
                    }
                    new_json.add(id, new_obj);
                }

                lastResponse = new_json.toString();
                boolean finalUpdate_now = update_now;
                writeResponseFileAsync(lastResponse).thenRun(() -> {
                    if (finalUpdate_now) {
                        sendResponse();
                    }
                });
            } catch (Exception e) {
                if (log)
                    LOGGER.error("Error while checking payments: {}", e.getMessage());
            }
        });
        service.shutdown();
    }

    private static String safeGetAsString(JsonObject obj, String key) {
        JsonElement elem = obj.get(key);
        if (elem != null && elem.isJsonPrimitive()) {
            return elem.getAsString();
        } else {
            return "";
        }
    }

    public static String check_online_player(String uuid_str, String mc_username) {
        uuid_str = uuid_str.replace("\"", "").trim();
        mc_username = mc_username.replace("\"", "").trim();

        if (serverInstance == null)
            return null;

        CompletableFuture<String> future = new CompletableFuture<>();

        String finalUuid_str = uuid_str;
        String finalMc_username = mc_username;
        serverInstance.execute(() -> {
            for (Player player : serverInstance.getPlayerList().getPlayers()) {
                String loopedPlayerUsername = player.getGameProfile().getName();
                UUID playerUUID = player.getUUID();
                if (finalUuid_str.equalsIgnoreCase("name") || finalUuid_str.isEmpty()) {
                    if (loopedPlayerUsername.equalsIgnoreCase(finalMc_username)) {
                        future.complete(loopedPlayerUsername);
                        return;
                    }
                } else {
                    if (playerUUID.toString().replace("-", "").equalsIgnoreCase(finalUuid_str)) {
                        future.complete(loopedPlayerUsername);
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

    public static ServerPlayer getPlayer(String mc_username) {
        for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
            String loopedPlayerUsername = player.getGameProfile().getName();
            if (loopedPlayerUsername.equals(mc_username)) {
                return player;
            }
        }
        return null;
    }

    public static String calculateHMAC(String server_id, String public_key, String private_key, Long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(private_key.getBytes(), HMAC_SHA1_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
            String datas = server_id + public_key + timestamp;
            byte[] rawHmac = mac.doFinal(datas.getBytes());
            String result = Base64.getEncoder().encodeToString(rawHmac);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void sendResponse() {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty()) {
            return;
        }
        try {
            long timestamp = new Date().getTime();
            URL url = new URL(API_URL);
            String macSignature = calculateHMAC(Tip4ServKey.getServerID(), Tip4ServKey.getPublicKey(), Tip4ServKey.getPrivateKey(), timestamp);
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            sendHttpRequest("update");
        } catch (Exception ignored) {
        }
    }

    public static String sendHttpRequest(String cmd) {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty()) {
            return "false";
        }
        try {
            long timestamp = new Date().getTime();
            String fileContent = readResponseFile();
            String jsonEncoded = URLEncoder.encode(fileContent.isEmpty() ? "{}" : fileContent, StandardCharsets.UTF_8);
            String macSignature = calculateHMAC(Tip4ServKey.getServerID(), Tip4ServKey.getPublicKey(), Tip4ServKey.getPrivateKey(), timestamp);
            String urlString = API_URL + "?id=" + Tip4ServKey.getServerID() + "&time=" + timestamp + "&json=" + jsonEncoded + "&get_cmd=" + cmd;
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
            if (cmd.equals("update")) {
                clearResponseFile();
            }
            return response.toString();
        } catch (Exception e) {
            return "false";
        }
    }

    public static void checkConnection(Entity entity) {

        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty() || Tip4ServKey.getPublicKey().isEmpty()) {
            if (entity == null) {
                LOGGER.warn("Please provide a correct apiKey in tip4serv/tip4serv.key file");
            } else {
                entity.sendSystemMessage(Component.literal("Please provide a correct apiKey in tip4serv/tip4serv.key file"));
            }
            return;
        }

        String response = sendHttpRequest("no");
        if (entity == null) {
            if (response.contains("[Tip4serv error]")) {
                LOGGER.error("Error while connecting to Tip4Serv API: " + response);
            } else {
                LOGGER.info("Connection to Tip4Serv API: " + response);
            }
        } else {
            if (response.contains("[Tip4serv error]")) {
                entity.sendSystemMessage(Component.literal("Error while connecting to Tip4Serv API: " + response));
            } else {
                entity.sendSystemMessage(Component.literal("Connection to Tip4Serv AP: " + response));
            }
        }
    }

    // ================================
    // Response.json file management system
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
            return "";
        }
    }

    public static T4SMain getINSTANCE() {
        return INSTANCE;
    }
}
