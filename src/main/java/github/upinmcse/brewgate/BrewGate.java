package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import github.upinmcse.brewgate.config.ConsulConfigManager;
import github.upinmcse.brewgate.config.GatewayConfig;
import github.upinmcse.brewgate.config.RedisConfig;
import github.upinmcse.brewgate.config.ServiceConfig;
import github.upinmcse.brewgate.handler.ReverseProxyHandler;
import github.upinmcse.brewgate.helper.GatewayHelper;
import github.upinmcse.brewgate.loadbalancer.ServerPool;
import github.upinmcse.brewgate.middleware.auth.JwtAuthMiddleware;
import github.upinmcse.brewgate.middleware.ratelimiter.QuotaMiddleware;
import github.upinmcse.brewgate.router.RouterState;
import org.kiwiproject.consul.Consul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

//mvn clean compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.BrewGate"

//curl http://localhost:8080/users/profile
//curl http://localhost:8080/products/123

public class BrewGate {

    private static final Logger log = LoggerFactory.getLogger(BrewGate.class);
    private static final AtomicReference<RouterState> globalRouter = new AtomicReference<>();
    private static RedisClient redisClient;

    public static void main(String[] args) throws Exception {
        log.info("BrewGate !");

        Yaml yaml = new Yaml(new Constructor(GatewayConfig.class, new LoaderOptions()));
        GatewayConfig gatewayConfig;

        try (InputStream inputStream = new FileInputStream("src/main/resources/application.yaml")) {
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

            if (currentConfig.getRedis() != null && currentConfig.getRedis().getAddress() != null) {
                initRedis(currentConfig.getRedis());
            }

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
                GatewayHelper.sendErrorResponse(exchange, 503, "503 Service Unavailable: Gateway is initializing");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            HttpHandler handler = currentRouter.match(path);
            if (handler != null) {
                handler.handle(exchange);
            } else {
                GatewayHelper.sendErrorResponse(exchange, 404, "404 Not Found: No matching route for " + path);
            }
        });

        log.info("BrewGate listening on port :{}", port);
        server.start();
    }

    private static RouterState buildRouter(GatewayConfig gatewayConfig, Consul consul) {
        log.info("Building new routing state...");
        RouterState state = new RouterState();

        // Middleware
        JwtAuthMiddleware jwtAuthMiddleware = null;
        if (gatewayConfig.getAuthentication() != null && gatewayConfig.getAuthentication().isEnabled()) {
            jwtAuthMiddleware = new JwtAuthMiddleware(gatewayConfig.getAuthentication());
        }

        if (gatewayConfig.getServices() != null) {
            for (ServiceConfig svc : gatewayConfig.getServices()) {
                if (svc.getConsulServiceName() == null || svc.getConsulServiceName().isBlank()) {
                    continue;
                }

                ServerPool pool = new ServerPool();
                pool.startConsulWatcher(consul, svc.getConsulServiceName());

                HttpHandler proxyHandler = new ReverseProxyHandler(pool);

                if (svc.getQuota() != null && svc.getQuota().isEnabled()) {
                    if (redisClient == null) {
                        throw new IllegalStateException("Service '" + svc.getName() + "' has quota enabled, but Redis is not configured!");
                    }
                    if (jwtAuthMiddleware == null) {
                        throw new IllegalStateException("Service '" + svc.getName() + "' has quota enabled, but Authentication is disabled. Quota requires User ID!");
                    }
                    proxyHandler = new QuotaMiddleware(svc.getName(), svc.getQuota(), redisClient).apply(proxyHandler);
                    log.info("Enabled Quota for service '{}': Limit {} req/{}",
                            svc.getName(), svc.getQuota().getLimit(), svc.getQuota().getPeriod());
                }

                if (jwtAuthMiddleware != null) {
                    proxyHandler = jwtAuthMiddleware.apply(proxyHandler);
                }

                state.addRoute(svc.getPath(), proxyHandler, pool);

                log.info("Registered route: {} -> Consul Service: '{}'", svc.getPath(), svc.getConsulServiceName());
            }
        }
        return state;
    }

    private static void initRedis(RedisConfig redisCfg) {
        String[] parts = redisCfg.getAddress().split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;

        var configBuilder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(2000)
                .socketTimeoutMillis(2000)
                .database(redisCfg.getDb());
        if (redisCfg.getPassword() != null && !redisCfg.getPassword().isBlank()) {
            configBuilder.password(redisCfg.getPassword());
        }

        redisClient = RedisClient.builder()
                .hostAndPort(host, port)
                .clientConfig(configBuilder.build())
                .build();

        log.info("Connected to Redis at {}:{} (DB: {}) using Jedis 8 RedisClient", host, port, redisCfg.getDb());
    }
}
