package github.upinmcse.brewgate.config;

import java.util.List;

public class GatewayConfig {
    private String gatewayPort;
    private List<ServiceConfig> services;
    private AuthConfig authentication;
    private RedisConfig redis;
    private TLSConfig tlsConfig;

    public GatewayConfig(){}

    public String getGatewayPort() {
        return gatewayPort;
    }

    public void setGatewayPort(String gatewayPort) {
        this.gatewayPort = gatewayPort;
    }

    public List<ServiceConfig> getServices() {
        return services;
    }

    public void setServices(List<ServiceConfig> services) {
        this.services = services;
    }

    public AuthConfig getAuthentication() {
        return authentication;
    }

    public void setAuthentication(AuthConfig authentication) {
        this.authentication = authentication;
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    public TLSConfig getTlsConfig() {
        return tlsConfig;
    }

    public void setTlsConfig(TLSConfig tlsConfig) {
        this.tlsConfig = tlsConfig;
    }
}
