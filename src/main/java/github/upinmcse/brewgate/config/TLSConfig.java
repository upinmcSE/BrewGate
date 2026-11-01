package github.upinmcse.brewgate.config;

public class TLSConfig {
    private boolean enabled;
    private String certFile;
    private String keyFile;
    private String httpsPort;

    public TLSConfig() {}

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCertFile() { return certFile; }

    public void setCertFile(String certFile) { this.certFile = certFile; }

    public String getKeyFile() { return keyFile; }

    public void setKeyFile(String keyFile) { this.keyFile = keyFile; }

    public String getHttpsPort() { return httpsPort; }

    public void setHttpsPort(String httpsPort) { this.httpsPort = httpsPort; }
}
