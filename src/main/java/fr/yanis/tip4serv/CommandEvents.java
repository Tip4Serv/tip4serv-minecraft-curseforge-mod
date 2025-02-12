package fr.yanis.tip4serv;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.checkerframework.checker.units.qual.C;

@Mod.EventBusSubscriber(modid = "tip4serv", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
public class CommandEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("store")
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
                        .then(Commands.argument("connect", StringArgumentType.string())
                                .executes(context -> {
                                    T4SMain.checkConnection(context.getSource().getEntity());
                                    return 1;
                                })
                        )
        );
    }

}
