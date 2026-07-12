package fr.yanis.tip4Serv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;

import com.google.inject.Inject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
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
    private static String response_path = "plugins/tip4serv/response.json";
    private static T4SMain pluginInstance;
    public static String lastResponse = "";

    private static final String API_BASE_URL = "https://api.tip4serv.com/v1/store/server/";
    private static final String USER_AGENT = "Tip4Serv-Velocity/1.0";
    private static final long COMMAND_DELAY_MS = 1000L;
    private static final ScheduledExecutorService COMMAND_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Tip4Serv-CommandScheduler");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public T4SMain(ProxyServer server) {
        this.server = server;
        pluginInstance = this;

        T4SConfig.initConfig();
        Tip4ServKey.init();

        System.out.println("[Tip4Serv] Plugin initialized");
    }

    public void launchRequest(boolean log) {
        try {
            String key_str = Tip4ServKey.getApiKey();
            if (!key_str.contains(".")) {
                if (log) System.out.println("[Tip4Serv] Please provide a correct apiKey in plugins/tip4serv/tip4serv.key file");
                return;
            }

            sendPendingUpdates();

            String json_string = httpGetCommands();
            if (json_string == null) {
                if (log) System.out.println("[Tip4Serv] Could not reach the Tip4Serv API.");
                return;
            }

            JsonElement parsed = JsonParser.parseString(json_string);
            if (!parsed.isJsonArray()) {
                if (log) System.out.println("[Tip4Serv] No pending payments found.");
                return;
            }
            JsonArray paymentsArr = parsed.getAsJsonArray();
            if (paymentsArr.size() == 0) {
                clearResponseFile();
                if (log) System.out.println("[Tip4Serv] No pending payments found.");
                return;
            }

            JsonObject updatePayload = new JsonObject();
            boolean hasExecutedCommands = false;
            final int[] delaySlot = {0};
            final ProxyServer srv = server;

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
                            try {
                                srv.getCommandManager().executeAsync(srv.getConsoleCommandSource(), cmdToRun);
                            } catch (Exception e) {
                                System.out.println("[Tip4Serv] Failed to execute command: " + cmdToRun);
                            }
                        }, delayMs, TimeUnit.MILLISECONDS);
                        new_cmds.addProperty(cmdId, "Executed");
                        hasExecutedCommands = true;
                    } else {
                        new_cmds.addProperty(cmdId, "Not Executed");
                    }
                }
                paymentUpdate.add("cmds", new_cmds);
                updatePayload.add(paymentId, paymentUpdate);
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
            if (log) System.out.println("[Tip4Serv] Error while checking payments: " + e.getMessage());
        }
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {

        server.getCommandManager().register("tip4proxy", new Tip4servCommand());

        Tip4ServKey.loadKey().thenRun(() -> launchRequest(true));

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> launchRequest(false), T4SConfig.getInterval(), T4SConfig.getInterval(), TimeUnit.MINUTES);

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
        return CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(Paths.get(response_path), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
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
            // API injoignable : on saute ce poll et on réessaie au prochain. Rien n'est perdu.
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

    public static class Tip4servCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            String connect = args.length >= 1 ? args[0] : "";
            if (connect.equalsIgnoreCase("connect")) {
                Tip4ServKey.loadKey().thenRun(() -> {
                    String key_str = Tip4ServKey.getApiKey();
                    if (!key_str.contains(".")) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text("§c[Tip4serv error] please paste your KEY (see MY SERVERS on Tip4serv.com) in the plugins/tip4serv/tip4serv.key file of your server and retype the command.§r"));
                    } else if (httpGetCommands() != null) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text("§a[Tip4Serv] Successfully connected to Tip4Serv API!§r"));
                    } else {
                        sender.sendMessage(net.kyori.adventure.text.Component.text("§c[Tip4serv error] Could not connect to Tip4Serv API. Check your key in plugins/tip4serv/tip4serv.key.§r"));
                    }
                });
            } else if (connect.equalsIgnoreCase("reload")) {
                try {
                    Tip4ServKey.loadKey().thenRun(() -> T4SMain.getInstance().launchRequest(true));
                    sender.sendMessage(net.kyori.adventure.text.Component.text("§a[Tip4Serv] Configuration reloaded§r"));
                } catch (Exception e) {
                    System.out.println("[Tip4Serv] Error reloading config: " + e.getMessage());
                    sender.sendMessage(net.kyori.adventure.text.Component.text("§c[Tip4serv error] " + e + "§r"));
                }
            } else {
                sender.sendMessage(net.kyori.adventure.text.Component.text("§aUse: /tip4proxy [connect/reload]§r"));
            }
        }
    }

    private static void clearResponseFile() {
        writeResponseFileAsync("");
    }

    public static T4SMain getInstance() {
        return pluginInstance;
    }
}
