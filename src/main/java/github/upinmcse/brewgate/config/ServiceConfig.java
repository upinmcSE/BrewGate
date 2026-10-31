package github.upinmcse.brewgate.config;

import java.util.List;

public class ServiceConfig {
    private String name;
    private String path;
    private List<String> backends;

    public ServiceConfig(){}

    public ServiceConfig(String name, String path, List<String> backends){
        this.name = name;
        this.path = path;
        this.backends = backends;
    }

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

    public List<String> getBackends() {
        return backends;
    }

    public void setBackends(List<String> backends) {
        this.backends = backends;
    }
}
