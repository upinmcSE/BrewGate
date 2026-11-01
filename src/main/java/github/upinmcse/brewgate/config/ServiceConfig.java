package github.upinmcse.brewgate.config;

public class ServiceConfig {
    private String name;
    private String path;
    private String consulServiceName;
    private QuotaConfig quota;

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

    public QuotaConfig getQuota() {
        return quota;
    }

    public void setQuota(QuotaConfig quota) {
        this.quota = quota;
    }
}
