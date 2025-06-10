package fr.yanis.tip4Serv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;

import javax.inject.Inject;
import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

@Plugin(id = "tip4serv", name = "Tip4Serv", version = "1.0-SNAPSHOT", authors = {"Yanis"})
public class T4SMain {

    private final ProxyServer server;
    private ScheduledExecutorService scheduler;
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA256";
    private static String key_path = "plugins/tip4serv/tip4serv.key";
    private static String response_path = "plugins/tip4serv/response.json";
    private static T4SMain pluginInstance;
    public static String lastResponse = "";

    private static final String API_URL = "https://api.tip4serv.com/payments_api_v2.php";

    @Inject
    public T4SMain(ProxyServer server) {
        this.server = server;
        pluginInstance = this;

        File file = new File(key_path);
        file.mkdirs();

        System.out.println("[Tip4Serv] Plugin initialized");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {

        Tip4ServKey.loadKey();

        server.getCommandManager().register("tip4proxy", new Tip4servCommand());
        System.out.println("[Tip4Serv] Registered command");

        scheduler = Executors.newSingleThreadScheduledExecutor();
        int requestIntervalMinutes = 1;
        System.out.println("[Tip4Serv] Starting scheduler with " + requestIntervalMinutes + " minute interval");

        scheduler.scheduleAtFixedRate(() -> {
            try {
                System.out.println("[Tip4Serv] Running scheduled task");
                String key_str = readFile(key_path, StandardCharsets.UTF_8).replaceAll("[\\n\t ]", "");
                if (!key_str.contains(".")) {
                    System.out.println("[Tip4Serv] Please provide a correct apiKey in plugins/tip4serv/tip4serv.key file");
                    return;
                }
                String Json_string = sendHttpRequest("yes");

                if (Json_string.contains("[Tip4serv info] No pending payments found")) {
                    return;
                } else if (Json_string.contains("[Tip4serv error]")) {
                    System.out.println(Json_string);
                    return;
                } else if (Json_string.contains("[Tip4serv info]")) {
                    System.out.println(Json_string);
                    return;
                }

                JsonArray infosArr = JsonParser.parseString(Json_string).getAsJsonArray();
                System.out.println("[Tip4Serv] Processing " + infosArr.size() + " payments");
                JsonObject new_json = new JsonObject();
                boolean update_now = false;

                for (int i1 = 0; i1 < infosArr.size(); i1++) {
                    JsonObject new_obj = new JsonObject();
                    JsonObject infos_obj = (JsonObject) infosArr.get(i1);
                    String player_connected, player_str, action;
                    String id = safeGetAsString(infos_obj, "id");
                    action = safeGetAsString(infos_obj, "action");
                    player_str = safeGetAsString(infos_obj, "player");
                    String uuidStr = safeGetAsString(infos_obj, "uuid");
                    JsonArray cmds = infos_obj.get("cmds").getAsJsonArray();

                    System.out.println("[Tip4Serv] Processing payment ID " + id + " for player " + player_str);
                    String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    new_obj.addProperty("date", date);
                    new_obj.addProperty("action", action);
                    JsonObject new_cmds = new JsonObject();

                    player_connected = check_online_player(safeGetAsString(infos_obj, "uuid"), safeGetAsString(infos_obj, "player"));
                    System.out.println("[Tip4Serv] Player connection status: " + (player_connected != null ? "Online" : "Offline"));

                    if (player_connected != null) player_str = player_connected;

                    List<String> cmds_failed = new ArrayList<>();
                    boolean redo_cmd = false;

                    System.out.println("[Tip4Serv] Processing " + cmds.size() + " commands");
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
                                server.getCommandManager().executeAsync(server.getConsoleCommandSource(), cmd_str);
                                new_cmds.addProperty(cmd_id, 3);
                                update_now = true;
                            }
                        } else if (state.equals("0")) {
                            server.getCommandManager().executeAsync(server.getConsoleCommandSource(), cmd_str);
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
                System.out.println("[Tip4Serv] Error in scheduled task:");
                e.printStackTrace();
            }
        }, 10, requestIntervalMinutes * 60, TimeUnit.SECONDS);
        System.out.println("[Tip4Serv] Scheduler started successfully");
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
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            Files.writeString(Paths.get(response_path), json, StandardCharsets.UTF_8);
            future.complete(null);
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    public static String check_online_player(String uuid_str, String mc_username) {
        for (Player player : pluginInstance.server.getAllPlayers()) {
            if (uuid_str.equals("name") || uuid_str.equals("")) {
                if (player.getUsername().equalsIgnoreCase(mc_username)) return player.getUsername();
            } else {
                if (player.getUniqueId().toString().replace("-", "").equals(uuid_str)) return player.getUsername();
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
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            sendHttpRequest("update");
        } catch (Exception e) {
            System.out.println("[Tip4Serv] Error when sending reponse :" + e.getMessage());
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
            System.out.println("[Tip4Serv] Response - " + cmd + " : " + response);
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

    public static class Tip4servCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            String connect = args.length >= 1 ? args[0] : "";
            String key_path = "plugins/tip4serv/tip4serv.key";
            if (connect.equalsIgnoreCase("connect")) {
                try {
                    System.out.println("[Tip4Serv] Connect command executed by " + sender.toString());
                    String key_str = readFile(key_path, StandardCharsets.UTF_8).replaceAll("[\\n\t ]", "");
                    if (!key_str.contains(".")) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text("§c[Tip4serv error] please paste your KEY (see MY SERVERS on Tip4serv.com) in the tip4serv/tip4serv.key file of your server and retype the command.§r"));
                    } else {
                        String lortu = sendHttpRequest(key_str);
                        if (lortu.contains("Tip4serv error")) {
                            sender.sendMessage(net.kyori.adventure.text.Component.text("§c" + lortu + "§r"));
                        } else {
                            sender.sendMessage(net.kyori.adventure.text.Component.text("§a" + lortu + "§r"));
                        }
                    }
                } catch (IOException e1) {
                    System.out.println("[Tip4Serv] Command execution error:");
                    e1.printStackTrace();
                    sender.sendMessage(net.kyori.adventure.text.Component.text("§c[Tip4serv error] " + e1 + "§r"));
                }
            } else {
                sender.sendMessage(net.kyori.adventure.text.Component.text("§aUse: /tip4serv connect§r"));
            }
        }
    }

    private static void clearResponseFile() {
        writeResponseFileAsync("");
    }
}