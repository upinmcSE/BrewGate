package github.upinmcse.brewgate.config;

public class AuthConfig {
    private boolean enabled;
    private String publicKeyPath;
    private String jwksUrl;
    private String expectedIssuer;

    public AuthConfig() {}
    public boolean isEnabled() {
        return enabled;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    public String getPublicKeyPath() {
        return publicKeyPath;
    }
    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }
    public String getJwksUrl() {
        return jwksUrl;
    }
    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }
    public String getExpectedIssuer() {
        return expectedIssuer;
    }
    public void setExpectedIssuer(String expectedIssuer) {
        this.expectedIssuer = expectedIssuer;
    }
}
