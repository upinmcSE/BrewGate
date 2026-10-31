package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

//mvn test-compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.MockBackend" -Dexec.args="<port> <service name>" -Dexec.classpathScope="test"

public class MockBackend {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        String serviceName = args.length > 1 ? args[1] : "user-service";
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", exchange -> {
            String requestBody = "";
            try (InputStream is = exchange.getRequestBody()) {
                requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            String clientIp = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            String customHeader = exchange.getRequestHeaders().getFirst("X-Custom-Header");

            System.out.printf("[Backend %d - %s] Received: %s %s (X-Forwarded-For: %s, CustomHeader: %s)%n",
                    port, serviceName, exchange.getRequestMethod(), exchange.getRequestURI(), clientIp, customHeader);

            String responseMessage = String.format("Hello from %s on port %d! Path: %s. Body was: [%s]%n",
                    serviceName, port, exchange.getRequestURI().getPath(), requestBody);

            byte[] bytes = responseMessage.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        System.out.printf("Started Mock Backend [%s] on port %d%n", serviceName, port);
        server.start();
    }
}
