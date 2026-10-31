package github.upinmcse.brewgate.config;

import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.cache.KVCache;
import org.kiwiproject.consul.model.kv.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.Optional;
import java.util.function.Consumer;

public class ConsulConfigManager implements AutoCloseable{

    private static final Logger log = LoggerFactory.getLogger(ConsulConfigManager.class);
    public static final String DEFAULT_CONFIG_KEY = "brew/config";

    private final KeyValueClient kvClient;
    private KVCache kvCache;

    public ConsulConfigManager(Consul consul) {
        this.kvClient = consul.keyValueClient();
    }

    public static GatewayConfig parseYaml(String yamlContent) {
        Yaml yaml = new Yaml(new Constructor(GatewayConfig.class, new LoaderOptions()));
        return yaml.load(yamlContent);
    }

    public GatewayConfig loadInitialConfig(String key) {
        log.info("Loading initial configuration from Consul KV key '{}'...", key);
        Optional<String> yamlOpt = kvClient.getValueAsString(key);
        if (yamlOpt.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "Config key '%s' not found in Consul KV! Please upload configuration first via curl.", key));
        }
        GatewayConfig config = parseYaml(yamlOpt.get());
        log.info("Initial configuration loaded successfully from Consul KV.");
        return config;
    }

    public void startConfigWatcher(String key, Consumer<GatewayConfig> onConfigReload) {
        this.kvCache = KVCache.newCache(kvClient, key, 10);
        this.kvCache.addListener(newValues -> {
            if (newValues == null || newValues.isEmpty()) {
                log.warn("Consul KV key '{}' is empty or was deleted!", key);
                return;
            }

            Value value = newValues.get(key);
            if (value == null) {

                value = newValues.values().iterator().next();
            }
            if (value != null) {
                value.getValueAsString().ifPresent(yaml -> {
                    try {
                        log.info("Configuration change detected in Consul KV! Reloading...");
                        GatewayConfig newConfig = parseYaml(yaml);

                        onConfigReload.accept(newConfig);
                        log.info("Hot reload complete! New configuration is ACTIVE with zero downtime.");
                    } catch (Exception e) {
                        log.error("Failed to reload new configuration: {}", e.getMessage(), e);
                    }
                });
            }
        });
        this.kvCache.start();
        log.info("Started Consul KVCache watcher on key '{}'", key);
    }

    @Override
    public void close() {
        if (kvCache != null) {
            kvCache.stop();
            log.info("Consul KVCache watcher stopped.");
        }
    }
}
