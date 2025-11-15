package com.example.outages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {
    private String interval = "10m";
    private String duration = "4h";
    private int maxOutagesTotal = 500;
    private double maxReachPctByMaxHour = 0.6;
    private int avgNodesPerFile = 120;
    private Ramp ramp = new Ramp();

    public static class Ramp {
        private String shape = "sigmoid"; // linear|exp|sigmoid
        private double a = 10; // sigmoid
        private double k = 2.5; // exp
        public String getShape() { return shape; }
        public void setShape(String shape) { this.shape = shape; }
        public double getA() { return a; }
        public void setA(double a) { this.a = a; }
        public double getK() { return k; }
        public void setK(double k) { this.k = k; }
    }

    public String getInterval() { return interval; }
    public void setInterval(String interval) { this.interval = interval; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public int getMaxOutagesTotal() { return maxOutagesTotal; }
    public void setMaxOutagesTotal(int maxOutagesTotal) { this.maxOutagesTotal = maxOutagesTotal; }
    public double getMaxReachPctByMaxHour() { return maxReachPctByMaxHour; }
    public void setMaxReachPctByMaxHour(double maxReachPctByMaxHour) { this.maxReachPctByMaxHour = maxReachPctByMaxHour; }
    public int getAvgNodesPerFile() { return avgNodesPerFile; }
    public void setAvgNodesPerFile(int avgNodesPerFile) { this.avgNodesPerFile = avgNodesPerFile; }
    public Ramp getRamp() { return ramp; }
    public void setRamp(Ramp ramp) { this.ramp = ramp; }
}
