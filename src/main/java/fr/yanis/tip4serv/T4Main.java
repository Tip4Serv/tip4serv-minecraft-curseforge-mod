package fr.yanis.tip4serv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import fr.yanis.tip4serv.neoforge.T4Config;
import fr.yanis.tip4serv.neoforge.Tip4ServKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

@Mod(T4Main.MODID)
public class T4Main {
    public static final String MODID = "tip4serv";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static MinecraftServer server;
    private ScheduledExecutorService scheduler;
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";
    private static String response_path = "config/tip4serv/response.json";
    private static T4Main pluginInstance;
    public static String lastResponse = "";

    private static final String API_BASE_URL = "https://api.tip4serv.com/v1/store/server/";
    private static final String USER_AGENT = "Tip4Serv-NeoForge/1.21";
    private static final long COMMAND_DELAY_MS = 1000L;
    private static final ScheduledExecutorService COMMAND_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Tip4Serv-CommandScheduler");
        t.setDaemon(true);
        return t;
    });

    public T4Main(IEventBus modEventBus, ModContainer modContainer) {
        pluginInstance = this;

        T4Config.initConfig();
        Tip4ServKey.init();

        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[Tip4Serv] Plugin initialized");
    }

    public void launchRequest(boolean log) {
        if (server == null) return;
        try {
            String key_str = Tip4ServKey.getApiKey();
            if (!key_str.contains(".")) {
                if (log) LOGGER.warn("[Tip4Serv] Please provide a correct apiKey in config/tip4serv/tip4serv.key file");
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

                String player_connected = check_online_player(uuidStr, player_str);
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
                            MinecraftServer srv = server;
                            if (srv == null) return;
                            srv.execute(() -> {
                                try {
                                    srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack(), cmdToRun);
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
                    final String finalName = finalPlayerStr;
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayerByName(finalName);
                        if (player != null) {
                            player.sendSystemMessage(Component.literal(T4Config.getMessageSuccess()));
                        }
                    });
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
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();

        Tip4ServKey.loadKey().thenRun(() -> launchRequest(true));

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Tip4Serv-Scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> launchRequest(false), T4Config.getInterval(), T4Config.getInterval(), TimeUnit.MINUTES);

        LOGGER.info("[Tip4Serv] Scheduler started successfully");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // Commande /storelink séparée sans permission
        dispatcher.register(
            Commands.literal("storelink")
                .executes(this::executeStoreLink)
        );

        // Commande /tip4serv avec permissions (cachée aux non-admins)
        dispatcher.register(
            Commands.literal("tip4serv")
                .requires(source -> source.hasPermission(4)) // Cache la commande aux non-admins
                .then(Commands.literal("connect")
                    .executes(this::executeConnect))
                .then(Commands.literal("reload")
                    .executes(this::executeReload))
                .executes(this::executeHelp)
        );
    }

    private int executeStoreLink(CommandContext<CommandSourceStack> context){
        CommandSourceStack source = context.getSource();

        String storeLink = T4Config.getStoreLink();
        String storeMessage = T4Config.getStoreMessage().replace("{storeLink}", storeLink);

        // Créer un composant cliquable
        Component linkComponent = Component.literal(storeMessage)
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink))
            );

        source.sendSuccess(() -> linkComponent, false);

        return 1;
    }

    private int executeConnect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        Tip4ServKey.loadKey().thenRun(() -> {
            String key_str = Tip4ServKey.getApiKey();
            if (!key_str.contains(".")) {
                source.sendFailure(Component.literal("§c[Tip4serv error] please paste your KEY (see MY SERVERS on Tip4serv.com) in the config/tip4serv/tip4serv.key file of your server and retype the command.§r"));
            } else if (httpGetCommands() != null) {
                source.sendSuccess(() -> Component.literal("§a[Tip4Serv] Successfully connected to Tip4Serv API!§r"), true);
            } else {
                source.sendFailure(Component.literal("§c[Tip4serv error] Could not connect to Tip4Serv API. Check your key in config/tip4serv/tip4serv.key.§r"));
            }
        });

        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            T4Config.loadConfig(); // Recharge la config
            Tip4ServKey.loadKey().thenRun(() -> T4Main.getInstance().launchRequest(true));
            source.sendSuccess(() -> Component.literal("§a[Tip4Serv] Configuration reloaded§r"), true);
        } catch (Exception e) {
            LOGGER.error("[Tip4Serv] Error reloading config: {}", e.getMessage());
            source.sendFailure(Component.literal("§c[Tip4serv error] " + e + "§r"));
        }

        return 1;
    }

    private int executeHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("§aUse: /tip4serv [connect/reload]§r"), false);
        return 1;
    }

    private static String safeGetAsString(JsonObject obj, String key) {
        JsonElement elem = obj.get(key);
        if (elem != null && elem.isJsonPrimitive()) {
            return elem.getAsString();
        } else {
            return "";
        }
    }

    private static CompletableFuture<Void> writeResponseFileAsync(String json) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(Paths.get(response_path), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    public static String check_online_player(String uuid_str, String mc_username) {
        if (server == null) return null;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (uuid_str.equals("name") || uuid_str.equals("")) {
                if (player.getName().getString().equalsIgnoreCase(mc_username)) return player.getName().getString();
            } else {
                if (player.getUUID().toString().replace("-", "").equals(uuid_str)) return player.getName().getString();
            }
        }
        return null;
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

    private static String readResponseFile() {
        try {
            File file = new File(response_path);
            if (!file.exists()) {
                return "";
            }
            return Files.readString(Paths.get(response_path));
        } catch (Exception e) {
            return "";
        }
    }

    private static void clearResponseFile() {
        writeResponseFileAsync("");
    }

    public static T4Main getInstance() {
        return pluginInstance;
    }
}
