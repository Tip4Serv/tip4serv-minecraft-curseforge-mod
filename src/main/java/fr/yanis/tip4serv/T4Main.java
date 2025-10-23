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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

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

@Mod(T4Main.MODID)
public class T4Main {
    public static final String MODID = "tip4serv";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static MinecraftServer server;
    private ScheduledExecutorService scheduler;
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";
    private static String key_path = "config/tip4serv/tip4serv.key";
    private static String response_path = "config/tip4serv/response.json";
    private static T4Main pluginInstance;
    public static String lastResponse = "";

    private static final String API_URL = "https://api.tip4serv.com/payments_api_v2.php";

    public T4Main(IEventBus modEventBus, ModContainer modContainer) {
        pluginInstance = this;

        T4Config.initConfig();
        Tip4ServKey.init();

        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[Tip4Serv] Plugin initialized");
    }

    public void launchRequest(boolean log){
        try {
            String key_str = Tip4ServKey.getApiKey();
            if (!key_str.contains(".")) {
                LOGGER.info("[Tip4Serv] Please provide a correct apiKey in config/tip4serv/tip4serv.key file");
                return;
            }

            String Json_string = sendHttpRequest("yes");

            if (Json_string.contains("[Tip4serv info] No pending payments found")) {
                return;
            } else if (Json_string.contains("[Tip4serv error]")) {
                if (log)
                    LOGGER.info(Json_string);
                return;
            } else if (Json_string.contains("[Tip4serv info]")) {
                if(log)
                    LOGGER.info(Json_string);
                return;
            } else if (Json_string.isEmpty() || Json_string.equals("false")) {
                if (log)
                    LOGGER.info("[Tip4Serv] No payments to process");
                return;
            }

            JsonArray infosArr = JsonParser.parseString(Json_string).getAsJsonArray();
            if (log)
                LOGGER.info("[Tip4Serv] Processing " + infosArr.size() + " payments");
            JsonObject new_json = new JsonObject();
            boolean update_now = false;

            for (int i1 = 0; i1 < infosArr.size(); i1++) {
                JsonObject infos_obj = (JsonObject) infosArr.get(i1);
                String id = safeGetAsString(infos_obj, "id");
                String action = safeGetAsString(infos_obj, "action");
                String player_str = safeGetAsString(infos_obj, "player");
                String uuidStr = safeGetAsString(infos_obj, "uuid");
                JsonArray cmds = infos_obj.get("cmds").getAsJsonArray();
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

                JsonObject new_obj = new JsonObject();

                if (log)
                    LOGGER.info("[Tip4Serv] Processing payment ID " + id + " for player " + player_str);

                new_obj.addProperty("date", date);
                new_obj.addProperty("action", action);
                JsonObject new_cmds = new JsonObject();

                String player_connected = check_online_player(uuidStr, player_str);

                if (log)
                    LOGGER.info("[Tip4Serv] Player connection status: " + (player_connected != null ? "Online" : "Offline"));

                if (player_connected != null) player_str = player_connected;

                List<String> cmds_failed = new ArrayList<>();
                boolean redo_cmd = false;

                for (int i2 = 0; i2 < cmds.size(); i2++) {
                    JsonElement elem = cmds.get(i2);

                    if (!elem.isJsonObject()) {
                        continue;
                    }

                    JsonObject cmds_obj = elem.getAsJsonObject();

                    String state = safeGetAsString(cmds_obj, "state");
                    String cmd_id = safeGetAsString(cmds_obj, "id");
                    String cmd_str = safeGetAsString(cmds_obj, "str").replace("{minecraft_username}", player_str);

                    if (state.equals("1")) {
                        if (player_connected == null) {
                            cmds_failed.add(cmd_id);
                            redo_cmd = true;
                        } else {
                            server.execute(() -> {
                                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd_str);
                            });
                            new_cmds.addProperty(cmd_id, 3);
                            update_now = true;
                        }
                    } else if (state.equals("0")) {
                        server.execute(() -> {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd_str);
                        });
                        new_cmds.addProperty(cmd_id, 3);
                        update_now = true;
                    } else {
                        new_cmds.addProperty(cmd_id, 14);
                        cmds_failed.add(cmd_id);
                        redo_cmd = true;
                    }
                }
                new_obj.add("cmds", new_cmds);
                new_obj.addProperty("status", redo_cmd ? 14 : 3);
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
            LOGGER.info("[Tip4Serv] Error in scheduled task:");
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();

        Tip4ServKey.loadKey().thenRun(() -> {
            launchRequest(true);
        });

        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            launchRequest(false);
        }, T4Config.getInterval(), T4Config.getInterval(), TimeUnit.MINUTES);

        LOGGER.info("[Tip4Serv] Scheduler started successfully");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("tip4serv").requires(source -> source.hasPermission(4))
                .then(Commands.literal("connect")
                    .executes(this::executeConnect))
                .then(Commands.literal("reload")
                    .executes(this::executeReload))
                .executes(this::executeHelp)
        );
    }

    private int executeConnect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        Tip4ServKey.loadKey().thenRun(() -> {
            String key_str = Tip4ServKey.getApiKey();
            if (!key_str.contains(".")) {
                source.sendFailure(Component.literal("§c[Tip4serv error] please paste your KEY (see MY SERVERS on Tip4serv.com) in the tip4serv/tip4serv.key file of your server and retype the command.§r"));
            } else {
                String lortu = sendHttpRequest(key_str);
                if (lortu.contains("Tip4serv error")) {
                    source.sendFailure(Component.literal("§c" + lortu + "§r"));
                } else {
                    source.sendSuccess(() -> Component.literal("§a" + lortu + "§r"), true);
                }
            }
        });

        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Tip4ServKey.loadKey().thenRun(() -> T4Main.getInstance().launchRequest(true));
            source.sendSuccess(() -> Component.literal("§a[Tip4Serv] Configuration reloaded§r"), true);
        } catch (Exception e) {
            LOGGER.info("[Tip4Serv] Error reloading config:");
            e.printStackTrace();
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

            int responseCode = connection.getResponseCode();
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            sendHttpRequest("update");
        } catch (Exception e) {
            LOGGER.info(e.getMessage());
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
            int responseCode = connection.getResponseCode();
            return response.toString();
        } catch (Exception e) {
            return "false";
        }
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
