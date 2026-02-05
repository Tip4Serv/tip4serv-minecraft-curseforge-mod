package fr.murga.tip4serv.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import fr.murga.tip4serv.Main;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

public class CommandTip4serv implements CommandExecutor {

    private final Main plugin;

    public CommandTip4serv(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // Handle /storelink command
        if (label.equalsIgnoreCase("storelink")) {
            handleStorelink(sender);
            return true;
        }

        // Handle /tip4serv subcommands
        String subCommand = args.length >= 1 ? args[0].toLowerCase() : "";

        switch (subCommand) {
            case "connect":
                handleConnect(sender);
                break;

            case "reload":
                handleReload(sender);
                break;

            default:
                sender.sendMessage("\u00A7aPlease use /tip4serv connect or /tip4serv reload\u00A7r");
                break;
        }

        return true;
    }

    /**
     * Handle /tip4serv connect - Test API connection.
     */
    private void handleConnect(CommandSender sender) {
        String key_path = "plugins/Tip4serv/tip4serv.key";

        try {
            String key_str = Main.readFile(key_path, StandardCharsets.UTF_8).replaceAll("[\\n\t ]", "");

            if (!key_str.contains(".")) {
                sender.sendMessage("\u00A7c[Tip4serv error] Please paste your KEY (see MY SERVERS on Tip4serv.com) in the tip4serv/tip4serv.key file and retype the command.\u00A7r");
                return;
            }

            plugin.getSchedulerAdapter().runAsync(() -> {
                try {
                    String result = Main.http_request(key_str, "no");

                    plugin.getSchedulerAdapter().runSync(() -> {
                        if (result.contains("Tip4Serv error")) {
                            sender.sendMessage("\u00A7c" + result + "\u00A7r");
                        } else {
                            sender.sendMessage("\u00A7a" + result + "\u00A7r");
                        }
                    });
                } catch (IOException e) {
                    plugin.getSchedulerAdapter().runSync(() -> {
                        sender.sendMessage("\u00A7c[Tip4serv error] " + e.getMessage() + "\u00A7r");
                    });
                }
            });

        } catch (IOException e) {
            sender.sendMessage("\u00A7c[Tip4serv error] " + e.getMessage() + "\u00A7r");
        }
    }

    /**
     * Handle /tip4serv reload - Force API poll and reload config.
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("tip4serv.reload") && !sender.isOp()) {
            sender.sendMessage("\u00A7c[Tip4serv] You don't have permission to reload.\u00A7r");
            return;
        }

        sender.sendMessage("\u00A7a[Tip4serv] Reloading configuration and forcing API check...\u00A7r");
        plugin.restartPolling();
        plugin.forcePoll();
        sender.sendMessage("\u00A7a[Tip4serv] Reload complete.\u00A7r");
    }

    /**
     * Handle /storelink - Show clickable store link.
     */
    private void handleStorelink(CommandSender sender) {
        String storeLink = plugin.getStoreLink();
        String storeMessage = plugin.getStoreMessage();

        if (sender instanceof Player) {
            Player player = (Player) sender;

            TextComponent message = new TextComponent(storeMessage);
            message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink));
            player.spigot().sendMessage(message);
        } else {
            sender.sendMessage(storeMessage);
        }
    }
}
