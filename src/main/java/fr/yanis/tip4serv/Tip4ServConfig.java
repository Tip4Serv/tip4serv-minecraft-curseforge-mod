package fr.yanis.tip4serv;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.config.ConfigTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class Tip4ServConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(Tip4ServConfig.class);
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    public static final ForgeConfigSpec.ConfigValue<Integer> INTERVAL;
    public static final ForgeConfigSpec.ConfigValue<String> STORE_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> STORE_LINK;
    private static final ForgeConfigSpec.ConfigValue<String> MESSAGE_SUCCESS;

    private static final String CONFIG_FILE_NAME = "tip4serv-config.toml";

    static {
        BUILDER.comment("Tip4Serv Configuration").push("general");
        API_KEY = BUILDER.comment("API Key for Tip4Serv integration")
                .define("apiKey", "");
        INTERVAL = BUILDER.comment("Interval in minutes between each check")
                .defineInRange("interval", 1, 1, Integer.MAX_VALUE);
        STORE_MESSAGE = BUILDER.comment("Link to the store page")
                .define("storeMessage", "Link to the store: {storeLink}");
        STORE_LINK = BUILDER.comment("Link to the store page")
                .define("storeLink", "https://tip4serv.com");
        MESSAGE_SUCCESS = BUILDER.comment("Message to display when the purchase is successful")
                .define("messageSuccess", "§a[Tip4Serv] Â§7You have just received your purchase thank you ");
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register() {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, CONFIG_FILE_NAME);
        LOGGER.info("Tip4Serv configuration registered.");
    }

    public static void loadConfig() {
        LOGGER.info("Loading Tip4Serv configuration...");
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, Path.of(net.minecraftforge.fml.ModLoadingContext.get().getActiveContainer().getModId()));
    }

    public static String getApiKey() {
        loadConfig();
        String key = API_KEY.get();
        return key == null || key.isEmpty() ? "" : key;
    }

    public static void setApiKey(String key) {
        if (key != null && !key.isEmpty()) {
            API_KEY.set(key);
            saveConfig();
        }
    }

    public static int getInterval() {
        loadConfig();
        int interval = INTERVAL.get();
        return interval;
    }

    public static void setInterval(int interval) {
        if (interval > 0) {
            INTERVAL.set(interval);
            saveConfig();
        }
    }

    public static String getStoreMessage() {
        loadConfig();
        String link = STORE_MESSAGE.get();
        return link == null || link.isEmpty() ? "" : link;
    }

    public static void setStoreMessage(String link) {
        if (link != null && !link.isEmpty()) {
            STORE_MESSAGE.set(link);
            saveConfig();
        }
    }

    public static String getStoreLink() {
        loadConfig();
        String link = STORE_LINK.get();
        return link == null || link.isEmpty() ? "" : link;
    }

    public static ForgeConfigSpec.ConfigValue<String> getMessageSuccess() {
        return MESSAGE_SUCCESS;
    }

    private static void saveConfig() {
        LOGGER.info("Saving Tip4Serv configuration...");
        SPEC.save();
    }
}
