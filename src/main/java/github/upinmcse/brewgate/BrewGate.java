package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import github.upinmcse.brewgate.config.ConsulConfigManager;
import github.upinmcse.brewgate.config.GatewayConfig;
import github.upinmcse.brewgate.config.RedisConfig;
import github.upinmcse.brewgate.config.ServiceConfig;
import github.upinmcse.brewgate.config.TlsManager;
import github.upinmcse.brewgate.handler.ReverseProxyHandler;
import github.upinmcse.brewgate.helper.GatewayHelper;
import github.upinmcse.brewgate.loadbalancer.ServerPool;
import github.upinmcse.brewgate.middleware.auth.JwtAuthMiddleware;
import github.upinmcse.brewgate.middleware.metric.MetricsMiddleware;
import github.upinmcse.brewgate.middleware.ratelimiter.QuotaMiddleware;
import github.upinmcse.brewgate.router.RouterState;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.kiwiproject.consul.Consul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

//mvn clean compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.BrewGate"

//curl http://localhost:8080/users/profile
//curl http://localhost:8080/products/123

public class BrewGate {

    private static final Logger log = LoggerFactory.getLogger(BrewGate.class);
    private static final AtomicReference<RouterState> globalRouter = new AtomicReference<>();
    private static RedisClient redisClient;
    private static PrometheusMeterRegistry prometheusMeterRegistry;

    public static void main(String[] args) throws Exception {
        log.info("BrewGate !");

        prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

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

        // 1. Create the root handler: routes each request to the matching backend handler
        HttpHandler rootHandler = exchange -> {
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
        };

        // 2. Create the handler that serves /metrics
        HttpHandler metricsHandler = exchange -> {
            // Prometheus scrapes with GET only; reject any other method
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String scrapeResponse = prometheusMeterRegistry.scrape();
            byte[] responseBytes = scrapeResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        };

        // 3. Start the server: supports both TLS (HTTPS) and non-TLS (HTTP) modes
        if (currentConfig.getTlsConfig() != null && currentConfig.getTlsConfig().isEnabled()) {
            int httpPort = Integer.parseInt(currentConfig.getGatewayPort());
            int httpsPort = Integer.parseInt(currentConfig.getTlsConfig().getHttpsPort());

            // A. Start the HTTP redirect server in the background
            TlsManager.startHttpToHttpsRedirectServer(httpPort, String.valueOf(httpsPort));

            // B. Start the main HTTPS server
            SSLContext sslContext = TlsManager.createSSLContext(
                    currentConfig.getTlsConfig().getCertFile(),
                    currentConfig.getTlsConfig().getKeyFile()
            );

            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(httpsPort), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            httpsServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpsServer.createContext("/metrics", metricsHandler);
            httpsServer.createContext("/", rootHandler);

            log.info("JavaGate (HTTPS) listening on port :{}", httpsPort);
            httpsServer.start();
        } else {
            // Plain HTTP mode
            int port = Integer.parseInt(currentConfig.getGatewayPort());
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/metrics", metricsHandler);
            server.createContext("/", rootHandler);
            log.info("JavaGate (HTTP) listening on port :{}", port);
            server.start();
        }
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

                // 1. Innermost layer: ReverseProxy forwards the request to the backend
                HttpHandler proxyHandler = new ReverseProxyHandler(pool);

                // 2. Quota layer: rate limiting per user
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

                // 3. JWT Auth layer: verify the RS256 signature and extract the user ID
                if (jwtAuthMiddleware != null) {
                    proxyHandler = jwtAuthMiddleware.apply(proxyHandler);
                }

                // 4. Outermost layer: MetricsMiddleware measures total latency and the resulting HTTP status
                proxyHandler = new MetricsMiddleware(svc.getName(), prometheusMeterRegistry).apply(proxyHandler);

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
