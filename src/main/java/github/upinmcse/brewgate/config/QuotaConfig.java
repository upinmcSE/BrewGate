package github.upinmcse.brewgate.config;

public class QuotaConfig {
    private boolean enabled;
    private long limit;
    private String period; // Ex: "10s", "1m", "1h"

    public QuotaConfig() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }
}
