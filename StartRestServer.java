import io.vertx.core.Vertx;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;

public class StartRestServer {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        
        System.out.println("Starting PeeGeeQ REST Server on port " + port);
        
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PeeGeeQRestServer(port), result -> {
            if (result.succeeded()) {
                System.out.println("✅ PeeGeeQ REST Server started successfully on port " + port);
                System.out.println("Health endpoint: http://localhost:" + port + "/health");
                System.out.println("Management API: http://localhost:" + port + "/api/v1/management/overview");
                System.out.println("Press Ctrl+C to stop the server");
            } else {
                System.err.println("❌ Failed to start server: " + result.cause().getMessage());
                result.cause().printStackTrace();
                System.exit(1);
            }
        });
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutting down PeeGeeQ REST Server...");
            vertx.close();
        }));
    }
}
