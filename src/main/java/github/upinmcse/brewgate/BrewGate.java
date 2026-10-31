package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import github.upinmcse.brewgate.config.ConsulConfigManager;
import github.upinmcse.brewgate.config.GatewayConfig;
import github.upinmcse.brewgate.config.ServiceConfig;
import github.upinmcse.brewgate.handler.ReverseProxyHandler;
import github.upinmcse.brewgate.loadbalancer.ServerPool;
import github.upinmcse.brewgate.router.RouterState;
import org.kiwiproject.consul.Consul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

//mvn clean compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.BrewGate"

//curl http://localhost:8080/users/profile
//curl http://localhost:8080/products/123

public class BrewGate {

    private static final Logger log = LoggerFactory.getLogger(BrewGate.class);
    private static final AtomicReference<RouterState> globalRouter = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        log.info("BrewGate !");

        Yaml yaml = new Yaml(new Constructor(GatewayConfig.class, new LoaderOptions()));
        GatewayConfig gatewayConfig;

        try(InputStream inputStream = new FileInputStream("src/main/resources/application.yaml")){
            gatewayConfig = yaml.load(inputStream);
        }

        if (gatewayConfig == null || gatewayConfig.getServices() == null) {
            log.warn("Config file is empty or invalid!");
            return;
        }

        Consul consul = Consul.builder()
                .withUrl("http://localhost:8500")
                .build();

        GatewayConfig currentConfig;
        try (ConsulConfigManager configManager = new ConsulConfigManager(consul)) {

            currentConfig = configManager.loadInitialConfig(ConsulConfigManager.DEFAULT_CONFIG_KEY);

            RouterState initialRouter = buildRouter(currentConfig, consul);
            globalRouter.set(initialRouter);

            configManager.startConfigWatcher(
                    ConsulConfigManager.DEFAULT_CONFIG_KEY,
                    newConfig -> {
                        RouterState newRouter = buildRouter(newConfig, consul);
                        RouterState oldRouter = globalRouter.getAndSet(newRouter);
                        if (oldRouter != null) {
                            oldRouter.close();
                        }
                    }
            );
        }

        int port = Integer.parseInt(currentConfig.getGatewayPort());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            RouterState currentRouter = globalRouter.get();
            if (currentRouter == null) {
                sendError(exchange, 503, "503 Service Unavailable: Gateway is initializing");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            HttpHandler handler = currentRouter.match(path);
            if (handler != null) {
                handler.handle(exchange);
            } else {
                sendError(exchange, 404, "404 Not Found: No matching route for " + path);
            }
        });
        log.info("BrewGate listening on port :{}", port);
        server.start();
    }

    private static RouterState buildRouter(GatewayConfig config, Consul consul) {
        log.info("Building new routing state...");
        RouterState state = new RouterState();

        if (config.getServices() != null) {
            for (ServiceConfig svc : config.getServices()) {
                if (svc.getConsulServiceName() == null || svc.getConsulServiceName().isBlank()) {
                    continue;
                }

                ServerPool pool = new ServerPool();
                pool.startConsulWatcher(consul, svc.getConsulServiceName());

                ReverseProxyHandler proxyHandler = new ReverseProxyHandler(pool);
                state.addRoute(svc.getPath(), proxyHandler, pool);

                log.info("Registered route: {} -> Consul Service: '{}'", svc.getPath(), svc.getConsulServiceName());
            }
        }
        return state;
    }

    private static void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, msg.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg.getBytes());
        }
    }
}
