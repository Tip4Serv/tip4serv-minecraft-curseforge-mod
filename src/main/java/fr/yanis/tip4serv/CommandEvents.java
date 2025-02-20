package fr.yanis.tip4serv;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "tip4serv", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
public class CommandEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("storelink")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            String storeMessage = Tip4ServConfig.getStoreMessage();
                            String storeLink = Tip4ServConfig.getStoreLink();

                            String[] parts = storeMessage.split("\\{storeLink}", 2);

                            MutableComponent message = new TextComponent(parts[0]);
                            MutableComponent link = new TextComponent(storeLink)
                                    .setStyle(Style.EMPTY
                                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink))
                                            .withUnderlined(true)
                                            .withBold(true)
                                    );
                            MutableComponent suffix = parts.length > 1 ? new TextComponent(parts[1]) : new TextComponent("");
                            MutableComponent finalMessage = message.append(link).append(suffix);

                            source.sendSuccess(finalMessage, false);
                            return 1;
                        })
        );

        event.getDispatcher().register(
                Commands.literal("tip4serv")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("connect")
                                .executes(context -> {
                                    Tip4ServKey.loadKey()
                                            .thenRun(() -> T4SMain.checkConnection(context.getSource().getEntity()))
                                            .exceptionally(e -> {
                                                context.getSource().sendSuccess(new TextComponent(e.getMessage().replace("java.lang.Exception: ", "")), false);
                                                return null;
                                            });
                                    return 1;
                                })
                        )
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    Tip4ServKey.loadKey().thenRun(() -> T4SMain.getINSTANCE().launchRequest(true)).exceptionally(e -> {
                                        context.getSource().sendSuccess(new TextComponent(e.getMessage().replace("java.lang.Exception: ", "")), false);
                                        return null;
                                    });
                                    context.getSource().sendSuccess(new TextComponent("Reloaded"), false);
                                    return 1;
                                })
                        )
        );
    }
}
