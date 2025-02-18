package fr.yanis.tip4serv;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "t4s", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
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

                            MutableComponent message = Component.literal(parts[0]);
                            MutableComponent link = Component.literal(storeLink)
                                    .withStyle(style -> style
                                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeLink))
                                            .withUnderlined(true)
                                            .withBold(true));
                            MutableComponent suffix = parts.length > 1 ? Component.literal(parts[1]) : Component.empty();
                            MutableComponent finalMessage = message.append(link).append(suffix);

                            source.sendSystemMessage(finalMessage);
                            return 1;
                        })
        );

        event.getDispatcher().register(
                Commands.literal("tip4serv")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("connect")
                                .executes(context -> {
                                    T4SMain.checkConnection(context.getSource().getEntity());
                                    return 1;
                                })
                        )
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    T4SMain.getINSTANCE().launchRequest(true);
                                    context.getSource().sendSystemMessage(Component.literal("Reloaded"));
                                    return 1;
                                })
                        )
        );
    }
}
