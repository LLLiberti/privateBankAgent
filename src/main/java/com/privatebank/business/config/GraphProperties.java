package com.privatebank.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "private-bank.graph")
public class GraphProperties {
    private boolean enabled = true;
    private int maxInitialNodes = 100;
    private int maxExpandNodes = 50;
    private int maxDepth = 3;
    private Duration queryTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxInitialNodes() { return maxInitialNodes; }
    public void setMaxInitialNodes(int value) { this.maxInitialNodes = positive(value, "maxInitialNodes"); }
    public int getMaxExpandNodes() { return maxExpandNodes; }
    public void setMaxExpandNodes(int value) { this.maxExpandNodes = positive(value, "maxExpandNodes"); }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int value) {
        if (value < 1 || value > 3) throw new IllegalArgumentException("maxDepth must be between 1 and 3");
        this.maxDepth = value;
    }
    public Duration getQueryTimeout() { return queryTimeout; }
    public void setQueryTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
        this.queryTimeout = value;
    }
    private int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
