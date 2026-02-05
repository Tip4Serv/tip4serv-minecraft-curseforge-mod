package fr.murga.tip4serv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import fr.murga.tip4serv.commands.CommandTip4serv;
import fr.murga.tip4serv.scheduler.SchedulerAdapter;
import fr.murga.tip4serv.scheduler.SchedulerFactory;
import fr.murga.tip4serv.scheduler.TaskHandle;

public class Main extends JavaPlugin implements Listener {

    public static Main plugin;

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static String key_path = "plugins/Tip4serv/tip4serv.key";
    private static String response_path = "plugins/Tip4serv/response.json";

    private SchedulerAdapter scheduler;
    private TaskHandle pollingTask;
    FileConfiguration config = this.getConfig();

    @Override
    public void onEnable() {
        plugin = this;
        scheduler = SchedulerFactory.createScheduler(this);

        try {
            File file_key = new File(key_path);
            if (!file_key.exists()) {
                new File("plugins/Tip4serv").mkdirs();
                FileWriter writer = new FileWriter(key_path);
                writer.write("");
                writer.close();
                Bukkit.getLogger().info("\u001B[36m[Tip4serv info] tip4serv.key has been created, please fill it in with your server key (in MY SERVERS on Tip4serv.com)\u001B[0m");
            }
        } catch (Exception e) {
            Bukkit.getLogger().info("\u001B[31m[Tip4serv error] can't create tip4serv.key\u001B[0m");
            return;
        }

        config.addDefault("request_interval_in_minutes", 1);
        config.addDefault("store_link", "https://tip4serv.com");
        config.addDefault("store_message", "Link to the store: {store_link}");
        config.addDefault("messageSuccess", "\u00A7a[Tip4Serv] \u00A77You have just received your purchase, thank you!");
        config.options().copyDefaults(true);
        saveConfig();

        getServer().getPluginManager().registerEvents(this, this);

        CommandTip4serv commandExecutor = new CommandTip4serv(this);
        getCommand("tip4serv").setExecutor(commandExecutor);
        getCommand("storelink").setExecutor(commandExecutor);

        startPolling();
    }

    @Override
    public void onDisable() {
        if (pollingTask != null) {
            pollingTask.cancel();
        }
        scheduler.cancelAllTasks();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduler.runAsync(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
            pollApi(false);
        });
    }

    public void startPolling() {
        try {
            long intervalTicks = get_config_scheduler_time(config.getDouble("request_interval_in_minutes", 1.0));
            pollingTask = scheduler.runAsyncRepeating(() -> pollApi(false), 20L, intervalTicks);
        } catch (Exception e) {
            getLogger().severe("[Tip4serv] Failed to start polling: " + e.getMessage());
        }
    }

    public void restartPolling() {
        if (pollingTask != null) {
            pollingTask.cancel();
        }
        reloadConfig();
        config = getConfig();
        startPolling();
    }

    public void forcePoll() {
        scheduler.runAsync(() -> pollApi(true));
    }

    @SuppressWarnings("unchecked")
    private void pollApi(boolean logOutput) {
        try {
            String key_str = readFile(key_path, StandardCharsets.UTF_8).replaceAll("[\\n\t ]", "");
            if (!key_str.contains(".")) return;

            String json_string = http_request(key_str, "yes");

            if (json_string.contains("[Tip4serv info] No pending payments found")) {
                clear_response_file();
                if (logOutput) {
                    Bukkit.getLogger().info("\u001B[36m" + json_string + "\u001B[0m");
                }
                return;
            } else if (json_string.contains("[Tip4serv error]")) {
                Bukkit.getLogger().info("\u001B[31m" + json_string + "\u001B[0m");
                return;
            } else if (json_string.contains("[Tip4serv info]")) {
                Bukkit.getLogger().info("\u001B[36m" + json_string + "\u001B[0m");
                return;
            }

            JSONParser parser = new JSONParser();
            JSONArray infosArr = (JSONArray) parser.parse(json_string);
            JSONObject new_json = new JSONObject();
            boolean update_now = false;
            List<CommandToExecute> commandsToExecute = new ArrayList<>();

            for (int i1 = 0; i1 < infosArr.size(); i1++) {
                JSONObject new_obj = new JSONObject();
                JSONObject infos_obj = (JSONObject) infosArr.get(i1);
                String player_connected, player_str, action;
                String id = infos_obj.get("id").toString();
                action = infos_obj.get("action").toString();
                player_str = infos_obj.get("player").toString();
                JSONArray cmds = (JSONArray) infos_obj.get("cmds");
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                new_obj.put("date", date);
                JSONObject new_cmds = new JSONObject();

                player_connected = check_online_player(
                        infos_obj.get("uuid").toString(),
                        infos_obj.get("player").toString()
                );

                if (player_connected != null) player_str = player_connected;

                boolean redo_cmd = false;
                final String finalPlayerStr = player_str;
                final String finalPlayerConnected = player_connected;

                for (int i2 = 0; i2 < cmds.size(); i2++) {
                    JSONObject cmds_obj = (JSONObject) cmds.get(i2);
                    String state = cmds_obj.get("state").toString();
                    String cmd_id = cmds_obj.get("id").toString();

                    if (state.equals("0") || (state.equals("1") && player_connected != null)) {
                        String cmd_str = cmds_obj.get("str").toString()
                                .replace("{minecraft_username}", finalPlayerStr);

                        commandsToExecute.add(new CommandToExecute(cmd_str, finalPlayerConnected != null ? finalPlayerStr : null));
                        new_cmds.put(cmd_id, 3);
                        update_now = true;
                    } else {
                        redo_cmd = true;
                    }
                }

                new_obj.put("cmds", new_cmds);

                if (redo_cmd && new_cmds.isEmpty()) {
                    new_obj.put("status", 14);
                } else if (redo_cmd) {
                    new_obj.put("status", 14);
                } else {
                    new_obj.put("status", 3);
                }

                new_obj.put("username", player_str);
                new_obj.put("action", action);
                new_json.put(id, new_obj);
            }

            FileWriter json_file_put = new FileWriter(response_path);
            json_file_put.write(new_json.toJSONString());
            json_file_put.close();

            for (CommandToExecute cmd : commandsToExecute) {
                scheduler.runSync(() -> {
                    getServer().dispatchCommand(getServer().getConsoleSender(), cmd.command);
                    Bukkit.getLogger().info("\u001B[36m[Tip4serv info] Command executed: " + cmd.command + "\u001B[0m");

                    if (cmd.playerName != null) {
                        Player player = Bukkit.getPlayer(cmd.playerName);
                        if (player != null && player.isOnline()) {
                            String successMsg = config.getString("messageSuccess",
                                    "\u00A7a[Tip4Serv] \u00A77You have just received your purchase, thank you!");
                            player.sendMessage(successMsg);
                        }
                    }
                });
            }

            if (update_now) {
                http_request(key_str, "update");
            }

        } catch (Exception e) {
            getLogger().severe("[Tip4serv] Polling error: " + e.getMessage());
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

    public static void clear_response_file() throws IOException {
        FileWriter json_file = new FileWriter(response_path);
        json_file.write("");
        json_file.close();
    }

    public static long get_config_scheduler_time(double intervalMinutes) {
        final long MIN_TICKS = 300L;
        long requestedTicks = (long) (intervalMinutes * 60.0 * 20.0);
        if (requestedTicks < MIN_TICKS) {
            Bukkit.getLogger().warning("[Tip4serv] Interval too low, using minimum 15 seconds");
            return MIN_TICKS;
        }
        return requestedTicks;
    }

    public static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    public static String check_online_player(String uuid_str, String mc_username) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String looped_player_username = player.getName();
            String playerUuid = player.getUniqueId().toString().replace("-", "");

            if (uuid_str != null && !uuid_str.equals("name") && !uuid_str.equals("")) {
                if (playerUuid.equals(uuid_str)) {
                    return looped_player_username;
                }
            }

            if (looped_player_username.equalsIgnoreCase(mc_username)) {
                return looped_player_username;
            }
        }
        return null;
    }

    public static String http_request(String key_str, String get_cmd) throws IOException {
        try {
            StringBuilder content = new StringBuilder();
            String json_encoded = "";
            String[] parts = key_str.split("\\.");

            if (parts.length != 3) {
                return "[Tip4serv error] key is invalid, make sure you have copied the entire key on Tip4serv.com panel in MY SERVERS (CTRL+A then CTRL+C).";
            }

            String server_id = parts[0];
            String private_key = parts[1];
            String public_key = parts[2];

            Date date = new Date();
            long timestamp = date.getTime();
            String MAC = calculateHMAC(server_id, public_key, private_key, timestamp);

            File res_json = new File(response_path);
            if (res_json.exists()) {
                String json = readFile(response_path, StandardCharsets.UTF_8);
                if (json != null && !json.trim().isEmpty()) {
                    json_encoded = json;
                }
            }

            URL url = new URL("https://api.tip4serv.com/payments_api_v2.php?id=" + server_id + "&time=" + timestamp + "&get_cmd=" + get_cmd);

            HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(5000);
            urlConnection.setReadTimeout(5000);
            urlConnection.setRequestMethod("POST");
            urlConnection.addRequestProperty("User-Agent", "Tip4Serv-Plugin/1.3.0");
            urlConnection.addRequestProperty("Accept", "application/json");
            urlConnection.addRequestProperty("Authorization", MAC);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setDoOutput(true);

            try (OutputStream os = urlConnection.getOutputStream()) {
                byte[] input = json_encoded.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                content.append(line);
            }
            bufferedReader.close();
            String result_str = content.toString();

            if (get_cmd.equals("update")) {
                clear_response_file();
            }

            return result_str;
        } catch (IOException e) {
            return "[Tip4serv error] Connection failed: " + e.getMessage();
        }
    }

    public static String calculateHMAC(String server_id, String public_key, String private_key, Long timestamp) {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(private_key.getBytes(), HMAC_SHA256_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(signingKey);
            String datas = server_id + public_key + timestamp;
            byte[] rawHmac = mac.doFinal(datas.getBytes());
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            Bukkit.getLogger().info("\u001B[31m[Tip4serv error] Unexpected error while creating hash: " + e.getMessage() + "\u001B[0m");
            throw new IllegalArgumentException();
        }
    }

    public SchedulerAdapter getSchedulerAdapter() {
        return scheduler;
    }

    public String getStoreLink() {
        return config.getString("store_link", "https://tip4serv.com");
    }

    public String getStoreMessage() {
        return config.getString("store_message", "Link to the store: {store_link}")
                .replace("{store_link}", getStoreLink());
    }
}
