package github.upinmcse.brewgate.config;

import java.util.List;

public class ServiceConfig {
    private String name;
    private String path;
    private String backendURL;

    public ServiceConfig(){}

    public ServiceConfig(String name, String path, String backendURL){
        this.name = name;
        this.path = path;
        this.backendURL = backendURL;
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

    public String getBackendURL() {
        return backendURL;
    }

    public void setBackendURL(String backendURL) {
        this.backendURL = backendURL;
    }
}
