package github.upinmcse.brewgate.config;

import java.util.List;

public class GatewayConfig {
    private String gatewayPort;
    private List<ServiceConfig> services;
    private AuthConfig authentication;

    public GatewayConfig(){}

    public GatewayConfig(String gatewayPort, List<ServiceConfig> services){
        this.gatewayPort = gatewayPort;
        this.services = services;
    }

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
}
