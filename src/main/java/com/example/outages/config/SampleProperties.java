package com.example.outages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sample")
public class SampleProperties {
    // Path to the sample.json relative or absolute on the same server
    private String path = "sample/sample.json";
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
