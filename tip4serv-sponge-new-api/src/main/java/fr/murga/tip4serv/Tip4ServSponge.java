package fr.murga.tip4serv;

import com.google.inject.Inject;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

@Plugin("tip4serv")
public class Tip4ServSponge {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final String API_BASE_URL = "https://api.tip4serv.com/v1/store/server/";

    @Inject
    private Logger logger;

    @Inject
    private Game game;

    @Inject
    private PluginContainer container;

    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;

    private Path keyPath;
    private Path responsePath;
    private Path configPath;

    private ScheduledTask pollingTask;
    private double requestIntervalMinutes = 1.0;
    private String storeLink = "https://tip4serv.com";
    private String storeMessage = "Link to the store: {store_link}";
    private String messageSuccess = "\u00A7a[Tip4Serv] \u00A7aYou have just received your purchase, thank you!";

    @Listener
    public void onServerStart(StartedEngineEvent<Server> event) {
        keyPath = configDir.resolve("tip4serv.key");
        responsePath = configDir.resolve("response.json");
        configPath = configDir.resolve("config.conf");

        try {
            Files.createDirectories(configDir);

            if (!Files.exists(keyPath)) {
                Files.write(keyPath, "".getBytes(StandardCharsets.UTF_8));
                logger.info("\u001B[36m[Tip4serv info] tip4serv.key has been created, please fill it in with your server key (in MY SERVERS on Tip4serv.com)\u001B[0m");
            }

            loadConfig();
        } catch (IOException e) {
            logger.error("\u001B[31m[Tip4serv error] Failed to initialize: " + e.getMessage() + "\u001B[0m");
            return;
        }

        startPolling();
        logger.info("\u001B[36m[Tip4Serv] Sponge plugin enabled (API v1)\u001B[0m");
    }

    @Listener
    public void onServerStop(StoppingEngineEvent<Server> event) {
        if (pollingTask != null) {
            pollingTask.cancel();
        }
        logger.info("[Tip4Serv] Plugin disabled");
    }

    @Listener
    public void onPlayerJoin(ServerSideConnectionEvent.Join event) {
        Sponge.asyncScheduler().submit(
            Task.builder()
                .delay(Duration.ofSeconds(3))
                .plugin(container)
                .execute(() -> pollApi(false))
                .build()
        );
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> event) {
        Command.Parameterized connectCmd = Command.builder()
            .permission("tip4serv.use")
            .executor(context -> {
                Sponge.asyncScheduler().submit(
                    Task.builder()
                        .plugin(container)
                        .execute(() -> {
                            String result = testConnection();
                            Sponge.server().scheduler().submit(
                                Task.builder()
                                    .plugin(container)
                                    .execute(() -> {
                                        Component msg = result.contains("[Tip4serv error]")
                                            ? Component.text(result, NamedTextColor.RED)
                                            : Component.text(result, NamedTextColor.GREEN);
                                        context.cause().audience().sendMessage(msg);
                                    })
                                    .build()
                            );
                        })
                        .build()
                );
                return CommandResult.success();
            })
            .build();

        Command.Parameterized reloadCmd = Command.builder()
            .permission("tip4serv.reload")
            .executor(context -> {
                restartPolling();
                Sponge.asyncScheduler().submit(
                    Task.builder()
                        .plugin(container)
                        .execute(() -> {
                            String result = pollApi(true);
                            Sponge.server().scheduler().submit(
                                Task.builder()
                                    .plugin(container)
                                    .execute(() -> {
                                        Component msg = result.contains("[Tip4serv error]")
                                            ? Component.text(result, NamedTextColor.RED)
                                            : Component.text(result, NamedTextColor.AQUA);
                                        context.cause().audience().sendMessage(msg);
                                    })
                                    .build()
                            );
                        })
                        .build()
                );
                return CommandResult.success();
            })
            .build();

        Command.Parameterized mainCmd = Command.builder()
            .addChild(connectCmd, "connect")
            .addChild(reloadCmd, "reload")
            .executor(context -> {
                context.cause().audience().sendMessage(
                    Component.text("[Tip4Serv] ", NamedTextColor.GREEN)
                        .append(Component.text("Please use /tip4serv connect or /tip4serv reload", NamedTextColor.GRAY))
                );
                return CommandResult.success();
            })
            .build();

        Command.Parameterized storelinkCmd = Command.builder()
            .executor(context -> {
                String message = storeMessage.replace("{store_link}", storeLink);
                context.cause().audience().sendMessage(
                    Component.text(message, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(storeLink))
                );
                return CommandResult.success();
            })
            .build();

        event.register(container, mainCmd, "tip4serv");
        event.register(container, storelinkCmd, "storelink");
    }

    private void loadConfig() {
        try {
            if (!Files.exists(configPath)) {
                String defaultConfig = "request_interval_in_minutes=1.0\n" +
                        "store_link=https://tip4serv.com\n" +
                        "store_message=Link to the store: {store_link}\n" +
                        "messageSuccess=§a[Tip4Serv] §aYou have just received your purchase, thank you!\n";
                Files.write(configPath, defaultConfig.getBytes(StandardCharsets.UTF_8));
            }

            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
            }

            String intervalStr = props.getProperty("request_interval_in_minutes", "1.0");
            try {
                requestIntervalMinutes = Double.parseDouble(intervalStr);
            } catch (NumberFormatException e) {
                requestIntervalMinutes = 1.0;
            }

            storeLink = props.getProperty("store_link", "https://tip4serv.com");
            storeMessage = props.getProperty("store_message", "Link to the store: {store_link}");
            messageSuccess = props.getProperty("messageSuccess", "\u00A7a[Tip4Serv] \u00A7aYou have just received your purchase, thank you!");

        } catch (IOException e) {
            logger.warn("[Tip4Serv] Could not load config, using defaults: " + e.getMessage());
        }
    }

    private void startPolling() {
        long intervalMs = getPollingIntervalMs();

        pollingTask = Sponge.asyncScheduler().submit(
            Task.builder()
                .delay(Duration.ofSeconds(1))
                .interval(Duration.ofMillis(intervalMs))
                .plugin(container)
                .execute(() -> pollApi(false))
                .build()
        );
    }

    private void restartPolling() {
        if (pollingTask != null) {
            pollingTask.cancel();
        }
        loadConfig();
        startPolling();
    }

    private String testConnection() {
        try {
            String keyStr = readFile(keyPath).replaceAll("[\\n\\t ]", "");
            if (!keyStr.contains(".")) {
                return "[Tip4serv error] Please paste your KEY (see MY SERVERS on Tip4serv.com) in the tip4serv.key file and retype the command.";
            }

            String[] parts = keyStr.split("\\.");
            if (parts.length != 3) {
                return "[Tip4serv error] Key is invalid, make sure you have copied the entire key on Tip4serv.com panel in MY SERVERS (CTRL+A then CTRL+C).";
            }

            String result = httpGetCommands(keyStr);

            if (result.contains("[Tip4serv error]")) {
                return result;
            }

            logger.info("\u001B[36m[Tip4serv info] Connection test successful\u001B[0m");
            return "[Tip4serv info] Successfully connected to Tip4Serv API!";
        } catch (Exception e) {
            return "[Tip4serv error] " + e.getMessage();
        }
    }

    private long getPollingIntervalMs() {
        long requestedMs = (long) (requestIntervalMinutes * 60.0 * 1000.0);
        long minMs = 15000L;
        if (requestedMs < minMs) {
            logger.warn("[Tip4serv] Interval too low, using minimum 15 seconds");
            return minMs;
        }
        return requestedMs;
    }

    @SuppressWarnings("unchecked")
    private String pollApi(boolean logOutput) {
        try {
            String keyStr = readFile(keyPath).replaceAll("[\\n\\t ]", "");
            if (!keyStr.contains(".")) {
                return "[Tip4serv info] No key configured";
            }

            String[] parts = keyStr.split("\\.");
            if (parts.length != 3) {
                return "[Tip4serv error] Key is invalid";
            }

            // send pending updates from last run
            sendPendingUpdates(keyStr);

            String jsonString = httpGetCommands(keyStr);

            if (jsonString.contains("[Tip4serv error]")) {
                logger.info("\u001B[31m" + jsonString + "\u001B[0m");
                return jsonString;
            }

            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(jsonString);

            if (!(parsed instanceof JSONArray)) {
                if (logOutput) {
                    logger.info("\u001B[36m[Tip4serv info] No pending payments found\u001B[0m");
                }
                return "[Tip4serv info] No pending payments found";
            }

            JSONArray paymentsArr = (JSONArray) parsed;

            if (paymentsArr.isEmpty()) {
                clearResponseFile();
                if (logOutput) {
                    logger.info("\u001B[36m[Tip4serv info] No pending payments found\u001B[0m");
                }
                return "[Tip4serv info] No pending payments found";
            }

            JSONObject updatePayload = new JSONObject();
            List<CommandToExecute> commandsToExecute = new ArrayList<>();
            boolean hasExecutedCommands = false;
            boolean hasPendingCommands = false;

            for (Object paymentObj : paymentsArr) {
                JSONObject payment = (JSONObject) paymentObj;
                String paymentId = payment.get("id").toString();
                String playerName = payment.get("player") != null ? payment.get("player").toString() : "";
                String minecraftUuid = payment.get("minecraft_uuid") != null ? payment.get("minecraft_uuid").toString() : "";
                JSONArray cmds = (JSONArray) payment.get("cmds");

                // check if player is connected
                String playerConnected = checkOnlinePlayer(minecraftUuid, playerName);
                String finalPlayerName = playerConnected != null ? playerConnected : playerName;

                JSONObject paymentUpdate = new JSONObject();
                paymentUpdate.put("action", "payment");
                JSONObject cmdsUpdate = new JSONObject();

                for (Object cmdObj : cmds) {
                    JSONObject cmd = (JSONObject) cmdObj;
                    String cmdId = cmd.get("id").toString();
                    String state = cmd.get("state").toString();
                    String cmdStr = cmd.get("str").toString().replace("{minecraft_username}", finalPlayerName);

                    // execute now or wait for player to be online
                    boolean canExecute = state.equalsIgnoreCase("Execute") ||
                                        (state.equalsIgnoreCase("Must be online") && playerConnected != null);

                    if (canExecute) {
                        commandsToExecute.add(new CommandToExecute(cmdStr, playerConnected != null ? finalPlayerName : null));
                        cmdsUpdate.put(cmdId, "Executed");
                        hasExecutedCommands = true;
                    } else {
                        cmdsUpdate.put(cmdId, "Not Executed");
                        hasPendingCommands = true;
                    }
                }

                paymentUpdate.put("cmds", cmdsUpdate);
                updatePayload.put(paymentId, paymentUpdate);
            }

            // save response locally in case of crash
            if (hasExecutedCommands) {
                Files.write(responsePath, updatePayload.toJSONString().getBytes(StandardCharsets.UTF_8));
            }

            // run commands on server thread
            for (CommandToExecute cmd : commandsToExecute) {
                Sponge.server().scheduler().submit(
                    Task.builder()
                        .plugin(container)
                        .execute(() -> {
                            try {
                                Sponge.server().commandManager().process(cmd.command);
                                logger.info("\u001B[32m[Tip4serv info] Command executed: " + cmd.command + "\u001B[0m");

                                if (cmd.playerName != null) {
                                    Optional<ServerPlayer> playerOpt = Sponge.server().player(cmd.playerName);
                                    if (playerOpt.isPresent() && playerOpt.get().isOnline()) {
                                        Component successText = Component.text("[Tip4Serv] ", NamedTextColor.GREEN)
                                            .append(Component.text("You have just received your purchase, thank you!", NamedTextColor.GREEN));
                                        playerOpt.get().sendMessage(successText);
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("[Tip4serv error] Failed to execute command: " + cmd.command + " - " + e.getMessage());
                            }
                        })
                        .build()
                );
            }

            // update API if commands executed
            if (hasExecutedCommands) {
                String updateResult = httpPostUpdate(keyStr, updatePayload.toJSONString());
                if (!updateResult.contains("[Tip4serv error]") && !updateResult.contains("[Tip4serv retry]")) {
                    clearResponseFile();
                    // only log if no pending commands
                    if (!hasPendingCommands && logOutput) {
                        logger.info("\u001B[32m[Tip4serv info] Commands status updated successfully\u001B[0m");
                    }
                } else if (updateResult.contains("[Tip4serv error]")) {
                    logger.warn("\u001B[33m[Tip4serv warning] Failed to update command status, will retry: " + updateResult + "\u001B[0m");
                }
                // keep response file for retry
            }

            return "[Tip4serv info] Commands processed";

        } catch (Exception e) {
            logger.error("[Tip4serv] Polling error: " + e.getMessage());
            return "[Tip4serv error] " + e.getMessage();
        }
    }

    private void sendPendingUpdates(String keyStr) {
        try {
            if (!Files.exists(responsePath)) return;

            String pendingJson = readFile(responsePath);
            if (pendingJson == null || pendingJson.trim().isEmpty()) return;

            String result = httpPostUpdate(keyStr, pendingJson);
            if (!result.contains("[Tip4serv error]")) {
                clearResponseFile();
                logger.info("\u001B[32m[Tip4serv info] Pending updates sent successfully\u001B[0m");
            }
        } catch (Exception e) {
            logger.warn("[Tip4serv] Could not send pending updates: " + e.getMessage());
        }
    }

    private static class CommandToExecute {
        final String command;
        final String playerName;

        CommandToExecute(String command, String playerName) {
            this.command = command;
            this.playerName = playerName;
        }
    }

    private void clearResponseFile() throws IOException {
        Files.write(responsePath, "".getBytes(StandardCharsets.UTF_8));
    }

    private String readFile(Path path) throws IOException {
        if (!Files.exists(path)) return "";
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String checkOnlinePlayer(String uuidStr, String mcUsername) {
        for (ServerPlayer player : Sponge.server().onlinePlayers()) {
            String loopedPlayerUsername = player.name();
            String playerUuid = player.uniqueId().toString().replace("-", "");

            if (uuidStr != null && !uuidStr.equals("name") && !uuidStr.isEmpty()) {
                String cleanUuid = uuidStr.replace("-", "");
                if (playerUuid.equalsIgnoreCase(cleanUuid)) {
                    return loopedPlayerUsername;
                }
            }

            if (loopedPlayerUsername.equalsIgnoreCase(mcUsername)) {
                return loopedPlayerUsername;
            }
        }
        return null;
    }

    private String httpGetCommands(String keyStr) {
        try {
            String[] parts = keyStr.split("\\.");
            if (parts.length != 3) {
                return "[Tip4serv error] Key is invalid, make sure you have copied the entire key on Tip4serv.com panel in MY SERVERS (CTRL+A then CTRL+C).";
            }

            String serverId = parts[0];
            String privateKey = parts[1];
            String publicKey = parts[2];

            long timestamp = System.currentTimeMillis() / 1000;
            String hmacToken = calculateHMAC(serverId, publicKey, privateKey, timestamp);

            URL url = new URL(API_BASE_URL + serverId + "/commands?time=" + timestamp);

            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Tip4Serv-Sponge/1.3.0");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer SERVER_KEY:" + hmacToken);

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                return content.toString();
            } else if (responseCode == 403) {
                return "[Tip4serv error] Invalid API key or insufficient permissions";
            } else if (responseCode == 404) {
                return "[Tip4serv error] Server not found";
            } else if (responseCode == 429 || responseCode == 503) {
                // ignore rate limit errors
                return "[]";
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                reader.close();
                return "[Tip4serv error] HTTP " + responseCode + ": " + error.toString();
            }

        } catch (IOException e) {
            // ignore connection errors
            return "[]";
        }
    }

    private String httpPostUpdate(String keyStr, String jsonPayload) {
        try {
            String[] parts = keyStr.split("\\.");
            if (parts.length != 3) {
                return "[Tip4serv error] Key is invalid";
            }

            String serverId = parts[0];
            String privateKey = parts[1];
            String publicKey = parts[2];

            long timestamp = System.currentTimeMillis() / 1000;
            String hmacToken = calculateHMAC(serverId, publicKey, privateKey, timestamp);

            URL url = new URL(API_BASE_URL + serverId + "/commands?time=" + timestamp);

            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "Tip4Serv-Sponge/1.3.0");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer SERVER_KEY:" + hmacToken);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                return content.toString();
            } else if (responseCode == 429 || responseCode == 503) {
                // retry later
                return "[Tip4serv retry]";
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                reader.close();
                return "[Tip4serv error] HTTP " + responseCode + ": " + error.toString();
            }

        } catch (IOException e) {
            // retry later
            return "[Tip4serv retry]";
        }
    }

    private String calculateHMAC(String serverId, String publicKey, String privateKey, long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(privateKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(signingKey);
            String data = serverId + publicKey + timestamp;
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            logger.error("\u001B[31m[Tip4serv error] Unexpected error while creating hash: " + e.getMessage() + "\u001B[0m");
            throw new IllegalArgumentException();
        }
    }
}
