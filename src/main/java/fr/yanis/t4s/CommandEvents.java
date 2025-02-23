package fr.yanis.t4s;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;

public class CommandEvents {

    public static void registerCommands() {
        // Fabric 1.18.x : on utilise CommandRegistrationCallback
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {

            // Commande /storelink
            dispatcher.register(
                    CommandManager.literal("storelink")
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                String storeMessage = Tip4ServConfig.getStoreMessage();
                                String storeLink = Tip4ServConfig.getStoreLink();

                                // On sépare avant/après {storeLink}
                                String[] parts = storeMessage.split("\\{storeLink}", 2);

                                // Comme Text.literal(...) n'existe pas en 1.18.x Yarn,
                                // on utilise new LiteralText(...) pour obtenir un MutableText
                                MutableText message = new LiteralText(parts[0]);
                                MutableText link = new LiteralText(storeLink)
                                        .setStyle(Style.EMPTY
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink))
                                                .withUnderline(true)
                                                .withBold(true)
                                        );
                                MutableText suffix = (parts.length > 1)
                                        ? new LiteralText(parts[1])
                                        : new LiteralText("");

                                // On assemble le texte final
                                MutableText finalMessage = message.append(link).append(suffix);

                                // On envoie le texte
                                source.sendFeedback(finalMessage, false);
                                return 1;
                            })
            );

            // Commande /tip4serv
            dispatcher.register(
                    CommandManager.literal("tip4serv")
                            // Niveau d'OP requis (3 = OP complet par défaut)
                            .requires(src -> src.hasPermissionLevel(3))
                            .then(CommandManager.literal("connect")
                                    .executes(context -> {
                                        // On recharge la clé, puis on lance checkConnection
                                        Tip4ServKey.loadKey()
                                                .thenRun(() -> T4SMain.checkConnection(context.getSource().getEntity()))
                                                .exceptionally(e -> {
                                                    context.getSource().sendFeedback(
                                                            new LiteralText(e.getMessage().replace("java.lang.Exception: ", "")),
                                                            false
                                                    );
                                                    return null;
                                                });
                                        return 1;
                                    })
                            )
                            .then(CommandManager.literal("reload")
                                    .executes(context -> {
                                        Tip4ServKey.loadKey().thenRun(() -> {
                                            // On relance la requête
                                            T4SMain.getINSTANCE().launchRequest(true);
                                        }).exceptionally(e -> {
                                            context.getSource().sendFeedback(
                                                    new LiteralText(e.getMessage().replace("java.lang.Exception: ", "")),
                                                    false
                                            );
                                            return null;
                                        });
                                        context.getSource().sendFeedback(new LiteralText("Reloaded"), false);
                                        return 1;
                                    })
                            )
            );
        });
    }
}