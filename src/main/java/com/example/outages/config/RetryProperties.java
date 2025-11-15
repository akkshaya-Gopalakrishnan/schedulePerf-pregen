package com.example.outages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retry")
public class RetryProperties {
    private int maxAttempts = 5;
    private int backoffSeconds = 1;
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getBackoffSeconds() { return backoffSeconds; }
    public void setBackoffSeconds(int backoffSeconds) { this.backoffSeconds = backoffSeconds; }
}
