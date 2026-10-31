package github.upinmcse.brewgate;

import com.sun.net.httpserver.HttpServer;
import github.upinmcse.brewgate.config.GatewayConfig;
import github.upinmcse.brewgate.config.ServiceConfig;
import github.upinmcse.brewgate.handler.ReverseProxyHandler;
import github.upinmcse.brewgate.loadbalancer.ServerPool;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

//mvn clean compile exec:java -Dexec.mainClass="github.upinmcse.brewgate.BrewGate"

//curl http://localhost:8080/users/profile
//curl http://localhost:8080/products/123

public class BrewGate {

    private static final Logger LOG = Logger.getLogger(BrewGate.class.getName());

    public static void main(String[] args) throws Exception {
        LOG.info("BrewGate !");

        Yaml yaml = new Yaml(new Constructor(GatewayConfig.class, new LoaderOptions()));
        GatewayConfig gatewayConfig;

        try(InputStream inputStream = new FileInputStream("src/main/resources/application.yaml")){
            gatewayConfig = yaml.load(inputStream);
        }

        if (gatewayConfig == null || gatewayConfig.getServices() == null) {
            LOG.warning("Config file is empty or invalid!");
        }

        int port = Integer.parseInt(Objects.requireNonNull(gatewayConfig).getGatewayPort());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        for (ServiceConfig svc: gatewayConfig.getServices()){
            ServerPool pool = new ServerPool();
            if (svc.getBackends() != null) {
                for (int i = 0; i < svc.getBackends().size(); i++) {
                    String backendUrl = svc.getBackends().get(i);
                    String backendId = String.format("%s-%d", svc.getName(), i + 1);
                    pool.addBackend(backendId, backendUrl);
                }
            }

            server.createContext(svc.getPath(), new ReverseProxyHandler(pool));
            LOG.info(String.format("Registered route: %s (%d backends)%n",
                    svc.getPath(), svc.getBackends() != null ? svc.getBackends().size() : 0));
        }

        LOG.info("BrewGate listening on port : " + port);
        server.start();
    }
}
