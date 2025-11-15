package com.example.outages;

import com.example.outages.config.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CampaignService {
    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
    private static final ZoneId PHOENIX = ZoneId.of("America/Phoenix");

    private final TaskScheduler scheduler;
    private final WebClient webClient;
    private final SchedulerProperties schedulerProps;
    private final TargetProperties targetProps;
    private final SampleProperties sampleProps;
    private final OutputProperties outputProps;
    private final RetryProperties retryProps;

    public CampaignService(TaskScheduler scheduler, WebClient webClient, SchedulerProperties schedulerProps,
                           TargetProperties targetProps, SampleProperties sampleProps, OutputProperties outputProps,
                           RetryProperties retryProps) {
        this.scheduler = scheduler;
        this.webClient = webClient;
        this.schedulerProps = schedulerProps;
        this.targetProps = targetProps;
        this.sampleProps = sampleProps;
        this.outputProps = outputProps;
        this.retryProps = retryProps;
    }

    // Java 11-friendly class for campaign status
    public static class Status {
        private final boolean running;
        private final int sentCount;
        private final int totalPlanned;
        private final String endpoint;
        private final Instant startedAt;
        private final Instant endsAt;

        public Status(boolean running, int sentCount, int totalPlanned, String endpoint,
                      Instant startedAt, Instant endsAt) {
            this.running = running;
            this.sentCount = sentCount;
            this.totalPlanned = totalPlanned;
            this.endpoint = endpoint;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
        }
        public boolean isRunning() { return running; }
        public int getSentCount() { return sentCount; }
        public int getTotalPlanned() { return totalPlanned; }
        public String getEndpoint() { return endpoint; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getEndsAt() { return endsAt; }
    }

    private volatile ScheduledFuture<?> future;
    private volatile boolean running = false;
    private volatile Instant startedAt;
    private volatile Instant endsAt;
    private final AtomicInteger sent = new AtomicInteger();
    private int totalPlanned;
    private List<Path> pregenFiles;

    public synchronized Status start() {
        if (running) return status();
        Objects.requireNonNull(targetProps.getEndpoint(), "target.endpoint is required");
        Objects.requireNonNull(sampleProps.getPath(), "sample.path is required");
        Objects.requireNonNull(outputProps.getDir(), "output.dir is required");

        Duration duration = parseDuration(schedulerProps.getDuration());
        Duration period = parseDuration(schedulerProps.getInterval());
        totalPlanned = (int)Math.ceil((double)duration.toMinutes()/period.toMinutes());
        sent.set(0);
        startedAt = Instant.now();
        endsAt = startedAt.plus(duration);

        pregenFiles = preGenerateAll(totalPlanned, period);
        running = true;

        if (Boolean.TRUE.equals(sendProps.isEnabled())) {
            running = true;
            future = scheduler.scheduleAtFixedRate(new Sender(), period);
            log.info("Scheduling sender every {}", period);
        } else {
            log.info("[DRY RUN] send.enabled=false → skipping scheduled uploads");
        }
        return status();
    }

    public synchronized Status stop() {
        if (future != null) future.cancel(false);
        running = false;
        return status();
    }

    public Status status() {
        return new Status(running, sent.get(), totalPlanned, targetProps.getEndpoint(), startedAt, endsAt);
    }

    private List<Path> preGenerateAll(int N, Duration period) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Path samplePath = Paths.get(sampleProps.getPath());
            String json = Files.readString(samplePath);
            Map<String,Object> sample = mapper.readValue(json, Map.class);

            Path outDir = Paths.get(outputProps.getDir());
            Files.createDirectories(outDir);

            return IntStream.rangeClosed(1, N)
                    .mapToObj(t -> {
                        try {
                            OffsetDateTime scheduledLocal = OffsetDateTime.now(PHOENIX).plusSeconds(period.getSeconds() * (t-1));
                            String outageId = "outage-" + scheduledLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssXXX"));
                            Map<String,Object> payload = generateFromSample(sample, t, N, outageId);
                            Path file = outDir.resolve(String.format("%03d-%s.json", t, outageId));
                            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
                            return file;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to pre-generate files", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> generateFromSample(Map<String,Object> sample, int t, int N, String outageId) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String,Object> root = mapper.readValue(mapper.writeValueAsBytes(sample), Map.class);

        Map<String,Object> outage = (Map<String,Object>) root.getOrDefault("outage", new HashMap<>());
        List<Map<String,Object>> nodes = (List<Map<String,Object>>) root.getOrDefault("deliveryNodes", new ArrayList<>());

        double r = ramp(t, N, schedulerProps.getRamp().getShape(), schedulerProps.getRamp().getA(), schedulerProps.getRamp().getK());
        double reachCap = schedulerProps.getMaxReachPctByMaxHour();
        double targetReach = reachCap * r;

        int maxOutagesTotal = schedulerProps.getMaxOutagesTotal();
        int outagesThisFile = Math.max(1, (int)Math.round(maxOutagesTotal * (1.0/N)));
        int avgNodes = Math.max(1, schedulerProps.getAvgNodesPerFile());
        int nodesThisFile = Math.max(1, (int)Math.round(avgNodes * (0.5 + r)));

        outage.put("id", outageId);
        outage.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        outage.put("scheduledFor", OffsetDateTime.now(PHOENIX).toString());
        root.put("outage", outage);

        Map<String,Object> nodeTemplate = nodes.isEmpty()? new HashMap<>() : nodes.get(0);
        List<Map<String,Object>> newNodes = new ArrayList<>();
        for (int i=1;i<=nodesThisFile;i++) {
            Map<String,Object> n = mapper.readValue(mapper.writeValueAsBytes(nodeTemplate), Map.class);
            n.put("dnId", String.format("dn-%05d", i));   // <── using dnId here
            int customers = Math.max(1, (int)Math.round(100 * (0.3 + r)));
            n.put("customersAffected", customers);
            newNodes.add(n);
        }
        root.put("deliveryNodes", newNodes);

        root.put("_meta", Map.of(
                "fileIndex", t,
                "totalPlanned", N,
                "targetReach", targetReach,
                "outagesThisFile", outagesThisFile,
                "nodesThisFile", nodesThisFile
        ));
        return root;
    }

    private class Sender implements Runnable {
        private int idx = 0;
        @Override public void run() {
            try {
                if (Instant.now().isAfter(endsAt)) { stop(); return; }
                if (idx >= pregenFiles.size()) { stop(); return; }
                Path next = pregenFiles.get(idx++);
                String body = Files.readString(next);
                postPayload(body);
                sent.incrementAndGet();
                log.info("Posted {}", next.getFileName());
            } catch (Exception e) {
                log.error("Sender error", e);
            }
        }
    }

    private void postPayload(String payloadJson) {
        WebClient.RequestBodySpec req = webClient.post().uri(targetProps.getEndpoint())
                .header("Content-Type", "application/json");
        String type = Optional.ofNullable(targetProps.getAuth()).map(TargetProperties.Auth::getType).orElse("none");
        if ("bearer".equalsIgnoreCase(type) && targetProps.getAuth().getToken()!=null) {
            req = req.header("Authorization", "Bearer " + targetProps.getAuth().getToken());
        } else if ("header".equalsIgnoreCase(type) && targetProps.getAuth().getHeaderName()!=null) {
            req = req.header(targetProps.getAuth().getHeaderName(), targetProps.getAuth().getHeaderValue());
        }
        req.bodyValue(payloadJson)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(Retry.backoff(retryProps.getMaxAttempts(), Duration.ofSeconds(retryProps.getBackoffSeconds()))
                        .maxBackoff(Duration.ofSeconds(30)))
                .block();
    }

    private static double ramp(int t, int N, String shape, double a, double k) {
        double x = Math.max(0, Math.min(1, (double)t / (double)N));
        switch (shape.toLowerCase()) {
            case "linear": return x;
            case "exp":
                double ek = Math.expm1(k);
                return Math.expm1(k * x) / (ek == 0 ? 1 : ek);
            default:
                double s = 1.0 / (1.0 + Math.exp(-a * (x - 0.5)));
                double s0 = 1.0 / (1.0 + Math.exp(-a * (0 - 0.5)));
                double s1 = 1.0 / (1.0 + Math.exp(-a * (1 - 0.5)));
                return (s - s0) / (s1 - s0);
        }
    }

    private static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length()-2)));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length()-1)));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length()-1)));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length()-1)));
        if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.substring(0, s.length()-1)));
        return Duration.parse(s);
    }
}
