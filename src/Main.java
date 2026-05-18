import config.ConfigLoader;
import config.ServerConfig;
import server.Server;

public class Main {
    public static void main(String[] args) {
        String configPath = "config.json";

        if (args.length > 0) {
            configPath = args[0];
        }

        try {
            ConfigLoader loader = new ConfigLoader();
            ServerConfig config = loader.load(configPath);

            Server server = new Server(config);
            server.start();

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}