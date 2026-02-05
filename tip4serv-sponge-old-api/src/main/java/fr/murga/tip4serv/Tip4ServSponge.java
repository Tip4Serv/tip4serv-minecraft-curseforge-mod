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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

@Plugin("tip4serv")
public class Tip4ServSponge {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

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
        logger.info("\u001B[36m[Tip4Serv] Sponge plugin enabled\u001B[0m");
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
                        "store_message=Link to the store: {store_link}\n";
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
            String result = httpRequest(keyStr, "no");

            if (result.contains("[Tip4serv error]")) {
                return result;
            }
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

            String jsonString = httpRequest(keyStr, "yes");

            if (jsonString.contains("[Tip4serv info] No pending payments found")) {
                clearResponseFile();
                if (logOutput) {
                    logger.info("\u001B[36m" + jsonString + "\u001B[0m");
                }
                return jsonString;
            } else if (jsonString.contains("[Tip4serv error]")) {
                logger.info("\u001B[31m" + jsonString + "\u001B[0m");
                return jsonString;
            } else if (jsonString.contains("[Tip4serv info]")) {
                logger.info("\u001B[36m" + jsonString + "\u001B[0m");
                return jsonString;
            } else if (jsonString.contains("[Tip4serv silent]")) {
                return jsonString;
            }

            JSONParser parser = new JSONParser();
            JSONArray infosArr = (JSONArray) parser.parse(jsonString);
            JSONObject newJson = new JSONObject();
            boolean updateNow = false;
            boolean hasPendingCommands = false;
            List<CommandToExecute> commandsToExecute = new ArrayList<>();

            for (int i1 = 0; i1 < infosArr.size(); i1++) {
                JSONObject newObj = new JSONObject();
                JSONObject infosObj = (JSONObject) infosArr.get(i1);
                String playerConnected, playerStr, action;
                String id = infosObj.get("id").toString();
                action = infosObj.get("action").toString();
                playerStr = infosObj.get("player").toString();
                JSONArray cmds = (JSONArray) infosObj.get("cmds");
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                newObj.put("date", date);
                JSONObject newCmds = new JSONObject();

                playerConnected = checkOnlinePlayer(
                        infosObj.get("uuid").toString(),
                        infosObj.get("player").toString()
                );

                if (playerConnected != null) playerStr = playerConnected;

                boolean redoCmd = false;
                final String finalPlayerStr = playerStr;
                final String finalPlayerConnected = playerConnected;

                for (int i2 = 0; i2 < cmds.size(); i2++) {
                    JSONObject cmdsObj = (JSONObject) cmds.get(i2);
                    String state = cmdsObj.get("state").toString();
                    String cmdId = cmdsObj.get("id").toString();

                    if (state.equals("0") || (state.equals("1") && playerConnected != null)) {
                        String cmdStr = cmdsObj.get("str").toString()
                                .replace("{minecraft_username}", finalPlayerStr);

                        commandsToExecute.add(new CommandToExecute(cmdStr, finalPlayerConnected != null ? finalPlayerStr : null));
                        newCmds.put(cmdId, 3);
                        updateNow = true;
                    } else {
                        redoCmd = true;
                    }
                }

                newObj.put("cmds", newCmds);

                if (redoCmd && newCmds.isEmpty()) {
                    newObj.put("status", 14);
                    hasPendingCommands = true;
                } else if (redoCmd) {
                    newObj.put("status", 14);
                    hasPendingCommands = true;
                } else {
                    newObj.put("status", 3);
                }

                newObj.put("username", playerStr);
                newObj.put("action", action);
                newJson.put(id, newObj);
            }

            Files.write(responsePath, newJson.toJSONString().getBytes(StandardCharsets.UTF_8));

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

            if (updateNow) {
                String updateResult = httpRequest(keyStr, "update");
                if (!updateResult.contains("[Tip4serv error]") && !updateResult.contains("[Tip4serv silent]")) {
                    clearResponseFile();
                    // only log if no pending commands
                    if (!hasPendingCommands && logOutput) {
                        logger.info("\u001B[32m[Tip4serv info] Commands status updated successfully\u001B[0m");
                    }
                } else if (updateResult.contains("[Tip4serv error]")) {
                    logger.warn("\u001B[33m[Tip4serv warning] Failed to update, will retry\u001B[0m");
                }
            }

            return "[Tip4serv info] Commands processed";

        } catch (Exception e) {
            logger.error("[Tip4serv] Polling error: " + e.getMessage());
            return "[Tip4serv error] " + e.getMessage();
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
                if (playerUuid.equals(uuidStr)) {
                    return loopedPlayerUsername;
                }
            }

            if (loopedPlayerUsername.equalsIgnoreCase(mcUsername)) {
                return loopedPlayerUsername;
            }
        }
        return null;
    }

    private String httpRequest(String keyStr, String getCmd) {
        try {
            StringBuilder content = new StringBuilder();
            String jsonEncoded = "";
            String[] parts = keyStr.split("\\.");

            if (parts.length != 3) {
                return "[Tip4serv error] key is invalid, make sure you have copied the entire key on Tip4serv.com panel in MY SERVERS (CTRL+A then CTRL+C).";
            }

            String serverId = parts[0];
            String privateKey = parts[1];
            String publicKey = parts[2];

            Date date = new Date();
            long timestamp = date.getTime();
            String mac = calculateHMAC(serverId, publicKey, privateKey, timestamp);

            if (Files.exists(responsePath)) {
                String json = readFile(responsePath);
                if (json != null && !json.trim().isEmpty()) {
                    jsonEncoded = json;
                }
            }

            URL url = new URL("https://api.tip4serv.com/payments_api_v2.php?id=" + serverId + "&time=" + timestamp + "&get_cmd=" + getCmd);

            HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(5000);
            urlConnection.setReadTimeout(5000);
            urlConnection.setRequestMethod("POST");
            urlConnection.addRequestProperty("User-Agent", "Tip4Serv-Plugin/1.3.0");
            urlConnection.addRequestProperty("Accept", "application/json");
            urlConnection.addRequestProperty("Authorization", mac);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setDoOutput(true);

            try (OutputStream os = urlConnection.getOutputStream()) {
                byte[] input = jsonEncoded.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = urlConnection.getResponseCode();

            if (responseCode == 503 || responseCode == 429) {
                return "[Tip4serv silent] API temporarily unavailable";
            }

            if (responseCode != 200) {
                return "[Tip4serv error] HTTP " + responseCode;
            }

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                content.append(line);
            }
            bufferedReader.close();
            String resultStr = content.toString();

            if (getCmd.equals("update")) {
                clearResponseFile();
            }

            return resultStr;
        } catch (IOException e) {
            return "[Tip4serv silent] " + e.getMessage();
        }
    }

    private String calculateHMAC(String serverId, String publicKey, String privateKey, Long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(privateKey.getBytes(), HMAC_SHA256_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(signingKey);
            String datas = serverId + publicKey + timestamp;
            byte[] rawHmac = mac.doFinal(datas.getBytes());
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            logger.error("\u001B[31m[Tip4serv error] Unexpected error while creating hash: " + e.getMessage() + "\u001B[0m");
            throw new IllegalArgumentException();
        }
    }
}
