package github.upinmcse.brewgate.config;

import java.util.List;

public class ServiceConfig {
    private String name;
    private String path;
    private String consulServiceName;
    public ServiceConfig() {}
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getPath() {
        return path;
    }
    public void setPath(String path) {
        this.path = path;
    }
    public String getConsulServiceName() {
        return consulServiceName;
    }
    public void setConsulServiceName(String consulServiceName) {
        this.consulServiceName = consulServiceName;
    }
}
