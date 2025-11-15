package com.example.outages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "output")
public class OutputProperties {
    // Folder where pre-generated files will be stored and picked from
    private String dir = "outages";
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
}
