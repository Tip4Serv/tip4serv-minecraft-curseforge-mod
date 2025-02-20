package fr.yanis.tip4serv;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

public class Tip4ServKey {

    private static final Logger LOGGER = LoggerFactory.getLogger(Tip4ServConfig.class);

    private static String API_KEY = "";

    public static void init(){
        File file = new File("tip4serv/tip4serv.key");

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static CompletableFuture<Void> loadKey(){
        CompletableFuture<Void> future = new CompletableFuture<>();
        File file = new File("tip4serv/tip4serv.key");
        if (file.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String key = reader.readLine();
                API_KEY = key;
                reader.close();
                if (key != null && !key.isEmpty()) {
                    String[] parts = key.split("\\.");
                    if (parts.length == 3) {
                        future.complete(null);
                    } else {
                        future.completeExceptionally(new Exception("Please provide a valid API key in tip4serv/tip4serv.key"));
                    }
                } else {
                    future.completeExceptionally(new Exception("Please provide a valid API key in tip4serv/tip4serv.key"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            init();
            future.completeExceptionally(new Exception("Please provide a valid API key in tip4serv/tip4serv.key"));
        }
        return future;
    }

    public static String getApiKey() {
        if (API_KEY == null || API_KEY.isEmpty())
            return "";
        else {
            return API_KEY;
        }
    }

    public static String getServerID(){
        return getApiKey().split("\\.")[0];
    }

    public static String getPrivateKey(){
        if (getApiKey().split("\\.").length < 3)
            return "";

        return getApiKey().split("\\.")[1];
    }

    public static String getPublicKey(){
        if (getApiKey().split("\\.").length < 3)
            return "";

        return getApiKey().split("\\.")[2];
    }

}
