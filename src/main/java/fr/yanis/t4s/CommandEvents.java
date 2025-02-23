package fr.yanis.t4s;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

public class CommandEvents {

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(
                    CommandManager.literal("storelink")
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                String storeMessage = Tip4ServConfig.getStoreMessage();
                                String storeLink = Tip4ServConfig.getStoreLink();
                                String[] parts = storeMessage.split("\\{storeLink}", 2);

                                MutableText message = Text.literal(parts[0]);
                                MutableText link = Text.literal(storeLink)
                                        .setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink))
                                                .withUnderline(true)
                                                .withBold(true)
                                        );
                                MutableText suffix = (parts.length > 1)
                                        ? Text.literal(parts[1])
                                        : Text.literal("");

                                MutableText finalMessage = message.append(link).append(suffix);

                                source.sendFeedback(() -> finalMessage, false);
                                return 1;
                            })
            );

            dispatcher.register(
                    CommandManager.literal("tip4serv")
                            .requires(src -> src.hasPermissionLevel(3))
                            .then(CommandManager.literal("connect")
                                    .executes(context -> {
                                        Tip4ServKey.loadKey()
                                                .thenRun(() -> T4SMain.checkConnection(context.getSource().getEntity()))
                                                .exceptionally(e -> {
                                                    context.getSource().sendFeedback(
                                                            () -> Text.literal(e.getMessage().replace("java.lang.Exception: ", "")),
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
                                            T4SMain.getINSTANCE().launchRequest(true);
                                        }).exceptionally(e -> {
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal(e.getMessage().replace("java.lang.Exception: ", "")),
                                                    false
                                            );
                                            return null;
                                        });
                                        context.getSource().sendFeedback(() -> Text.literal("Reloaded"), false);
                                        return 1;
                                    })
                            )
            );
        });
    }
}
