package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpServer;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

//mvn test-compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.MockBackend" -Dexec.args="<port> <service name>" -Dexec.classpathScope="test"

public class MockBackend {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        String serviceName = args.length > 1 ? args[1] : "user-service";
        String serviceId = String.format("%s-%d", serviceName, port);

        Consul consul = Consul.builder()
                .withUrl("http://localhost:8500")
                .build();

        Registration.RegCheck check = Registration.RegCheck.http(
                String.format("http://127.0.0.1:%d/health", port), 5, 1
        );
        Registration registration = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address("127.0.0.1")
                .port(port)
                .check(check)
                .build();
        consul.agentClient().register(registration);

        System.out.printf("[Mock Backend] Registered '%s' (ID: %s) with Consul via KiwiProject.%n", serviceName, serviceId);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/health", exchange -> {
            String ok = "OK";
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, ok.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok.getBytes(StandardCharsets.UTF_8));
            }
        });

        server.createContext("/", exchange -> {
            String responseMessage = String.format("Hello from %s on port %d! Path: %s%n",
                    serviceName, port, exchange.getRequestURI().getPath());
            byte[] bytes = responseMessage.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        System.out.printf("[Mock Backend] Listening on port %d...%n", port);
        server.start();
    }
}
