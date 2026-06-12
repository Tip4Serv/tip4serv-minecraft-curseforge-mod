package fr.yanis.t4s;

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
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.concurrent.*;

public class T4SMain implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(T4SMain.class);
    private static T4SMain INSTANCE;
    private static MinecraftServer serverInstance = null;
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";
    public static String lastResponse = "";
    private static final String API_BASE_URL = "https://api.tip4serv.com/v1/store/server/";
    private static final String USER_AGENT = "Tip4Serv-Fabric/1.19.4";
    private static final String RESPONSE_FILE_PATH = "tip4serv/response.json";
    private static final long COMMAND_DELAY_MS = 1000L;
    private static final ScheduledExecutorService COMMAND_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Tip4Serv-CommandScheduler");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitialize() {
        LOGGER.info("[Tip4Serv] Initializing mod (Fabric 1.19.4).");
        INSTANCE = this;
        Tip4ServConfig.initConfig();
        Tip4ServKey.init();
        CommandEvents.registerCommands();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            Tip4ServKey.loadKey()
                    .thenRun(() -> launchRequest(true))
                    .exceptionally(e -> {
                        LOGGER.error(e.getMessage());
                        return null;
                    });
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
        if (serverInstance == null) return;
        ExecutorService service = Executors.newSingleThreadExecutor();
        service.execute(() -> {
            try {
                if (!Tip4ServKey.getApiKey().contains(".")) {
                    if (log) LOGGER.warn("[Tip4Serv] Please provide a correct apiKey in tip4serv/tip4serv.key");
                    return;
                }

                sendPendingUpdates();

                String json_string = httpGetCommands();
                if (json_string == null) {
                    if (log) LOGGER.warn("[Tip4Serv] Could not reach the Tip4Serv API.");
                    return;
                }

                JsonElement parsed = JsonParser.parseString(json_string);
                if (!parsed.isJsonArray()) {
                    if (log) LOGGER.info("[Tip4Serv] No pending payments found.");
                    return;
                }
                JsonArray paymentsArr = parsed.getAsJsonArray();
                if (paymentsArr.size() == 0) {
                    clearResponseFile();
                    if (log) LOGGER.info("[Tip4Serv] No pending payments found.");
                    return;
                }

                JsonObject updatePayload = new JsonObject();
                boolean hasExecutedCommands = false;
                final int[] delaySlot = {0};

                for (int i1 = 0; i1 < paymentsArr.size(); i1++) {
                    JsonObject payment = paymentsArr.get(i1).getAsJsonObject();
                    String paymentId = safeGetAsString(payment, "id");
                    String player_str = safeGetAsString(payment, "player");
                    String uuidStr = safeGetAsString(payment, "minecraft_uuid");
                    JsonArray cmds = payment.get("cmds").getAsJsonArray();

                    String player_connected = checkOnlinePlayer(uuidStr, player_str);
                    if (player_connected != null) player_str = player_connected;
                    final String finalPlayerStr = player_str;

                    JsonObject paymentUpdate = new JsonObject();
                    paymentUpdate.addProperty("action", "payment");
                    JsonObject new_cmds = new JsonObject();
                    boolean pendingForThisPayment = false;

                    for (int i2 = 0; i2 < cmds.size(); i2++) {
                        JsonElement elem = cmds.get(i2);
                        if (!elem.isJsonObject()) continue;
                        JsonObject cmds_obj = elem.getAsJsonObject();
                        String state = safeGetAsString(cmds_obj, "state");
                        String cmdId = safeGetAsString(cmds_obj, "id");
                        String cmdStr = safeGetAsString(cmds_obj, "str").replace("{minecraft_username}", finalPlayerStr);

                        boolean canExecute = state.equalsIgnoreCase("Execute")
                                || (state.equalsIgnoreCase("Must be online") && player_connected != null);

                        if (canExecute) {
                            final String cmdToRun = cmdStr;
                            final long delayMs = delaySlot[0] * COMMAND_DELAY_MS;
                            delaySlot[0]++;
                            COMMAND_SCHEDULER.schedule(() -> {
                                MinecraftServer server = serverInstance;
                                if (server == null) return;
                                server.execute(() -> {
                                    try {
                                        ServerCommandSource source = new ServerCommandSource(
                                                CommandOutput.DUMMY,
                                                new Vec3d(0, 0, 0),
                                                new Vec2f(0, 0),
                                                server.getOverworld(),
                                                4,
                                                "Tip4Serv",
                                                Text.literal("Tip4Serv"),
                                                server,
                                                null
                                        );
                                        server.getCommandManager().getDispatcher().execute(cmdToRun, source);
                                    } catch (Exception e) {
                                        LOGGER.warn("[Tip4Serv] Failed to execute command: {}", cmdToRun);
                                    }
                                });
                            }, delayMs, TimeUnit.MILLISECONDS);
                            new_cmds.addProperty(cmdId, "Executed");
                            hasExecutedCommands = true;
                        } else {
                            new_cmds.addProperty(cmdId, "Not Executed");
                            pendingForThisPayment = true;
                        }
                    }
                    paymentUpdate.add("cmds", new_cmds);
                    updatePayload.add(paymentId, paymentUpdate);

                    if (!pendingForThisPayment && player_connected != null) {
                        ServerPlayerEntity player = getPlayer(finalPlayerStr);
                        if (player != null) player.sendMessage(Text.of(Tip4ServConfig.getMessageSuccess()));
                    }
                }

                lastResponse = updatePayload.toString();
                if (hasExecutedCommands) {
                    writeResponseFileAsync(lastResponse).thenRun(() -> {
                        if (httpPostUpdate(lastResponse)) {
                            clearResponseFile();
                        }
                    });
                }
            } catch (Exception e) {
                if (log) LOGGER.error("[Tip4Serv] Error while checking payments: {}", e.getMessage());
            }
        });
        service.shutdown();
    }

    private static String safeGetAsString(JsonObject obj, String key) {
        JsonElement elem = obj.get(key);
        return (elem != null && elem.isJsonPrimitive()) ? elem.getAsString() : "";
    }

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
                if (finalUuidStr.equalsIgnoreCase("name") || finalUuidStr.isEmpty()) {
                    if (currentName.equalsIgnoreCase(finalMcUsername)) {
                        future.complete(currentName);
                        return;
                    }
                } else {
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

    public static ServerPlayerEntity getPlayer(String mcUsername) {
        if (serverInstance == null) return null;
        for (ServerPlayerEntity player : serverInstance.getPlayerManager().getPlayerList()) {
            if (player.getGameProfile().getName().equals(mcUsername)) return player;
        }
        return null;
    }

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

    public static String httpGetCommands() {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty()) {
            return null;
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String hmac = calculateHMAC(Tip4ServKey.getServerID(), Tip4ServKey.getPublicKey(), Tip4ServKey.getPrivateKey(), timestamp);
            URL url = new URL(API_BASE_URL + Tip4ServKey.getServerID() + "/commands?time=" + timestamp);

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer SERVER_KEY:" + hmac);

            if (connection.getResponseCode() == 200) {
                return readStream(connection.getInputStream());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean httpPostUpdate(String jsonPayload) {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() || Tip4ServKey.getPrivateKey().isEmpty()) {
            return false;
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String hmac = calculateHMAC(Tip4ServKey.getServerID(), Tip4ServKey.getPublicKey(), Tip4ServKey.getPrivateKey(), timestamp);
            URL url = new URL(API_BASE_URL + Tip4ServKey.getServerID() + "/commands?time=" + timestamp);

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer SERVER_KEY:" + hmac);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static void sendPendingUpdates() {
        String pending = readResponseFile();
        if (pending == null || pending.trim().isEmpty()) {
            return;
        }
        if (httpPostUpdate(pending)) {
            clearResponseFile();
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    public static void checkConnection(Entity entity) {
        if (Tip4ServKey.getApiKey().isEmpty() || Tip4ServKey.getServerID().isEmpty() ||
                Tip4ServKey.getPrivateKey().isEmpty() || Tip4ServKey.getPublicKey().isEmpty()) {
            if (entity == null) {
                LOGGER.warn("[Tip4Serv] Please provide a correct apiKey in tip4serv/tip4serv.key file");
            } else {
                entity.sendMessage(Text.of("Please provide a correct apiKey in tip4serv/tip4serv.key file"));
            }
            return;
        }
        boolean connected = httpGetCommands() != null;
        if (entity == null) {
            if (connected) {
                LOGGER.info("[Tip4Serv] Successfully connected to Tip4Serv API");
            } else {
                LOGGER.error("[Tip4Serv] Could not connect to Tip4Serv API.");
            }
        } else {
            if (connected) {
                entity.sendMessage(Text.of("Successfully connected to Tip4Serv API"));
            } else {
                entity.sendMessage(Text.of("Could not connect to Tip4Serv API."));
            }
        }
    }

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
        writeResponseFileAsync("").join();
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
