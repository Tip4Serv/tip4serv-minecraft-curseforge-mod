package fr.yanis.tip4serv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

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

    public static void loadKey(){
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
                        T4SMain.checkConnection(null);
                    } else {
                        LOGGER.info("Please provide a valid API key in tip4serv/tip4serv.key");
                    }
                } else {
                    LOGGER.info("Please provide a valid API key in tip4serv/tip4serv.key");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            init();
        }
    }

    public static String getApiKey() {
        if (API_KEY == null || API_KEY.isEmpty())
            LOGGER.info("Please provide a valid API key in tip4serv/tip4serv.key");
        else {
            return API_KEY;
        }
        return "";
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
