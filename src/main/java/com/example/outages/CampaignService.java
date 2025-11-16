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
    private final SendProperties sendProps;

    public CampaignService(TaskScheduler scheduler,
                           WebClient webClient,
                           SchedulerProperties schedulerProps,
                           TargetProperties targetProps,
                           SampleProperties sampleProps,
                           OutputProperties outputProps,
                           RetryProperties retryProps,
                           SendProperties sendProps) {
        this.scheduler = scheduler;
        this.webClient = webClient;
        this.schedulerProps = schedulerProps;
        this.targetProps = targetProps;
        this.sampleProps = sampleProps;
        this.outputProps = outputProps;
        this.retryProps = retryProps;
        this.sendProps = sendProps;
    }

    /** Java 11-friendly status DTO (no records). */
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

    /** Start a campaign: always pre-generate; schedule sending only if send.enabled=true. */
    public synchronized Status start() {
        if (running) return status();

        Objects.requireNonNull(sampleProps.getPath(), "sample.path is required");
        Objects.requireNonNull(outputProps.getDir(), "output.dir is required");

        boolean sending = sendProps.isEnabled(); // assumes boolean + isEnabled()
        if (sending) {
            Objects.requireNonNull(targetProps.getEndpoint(),
                    "target.endpoint is required when send.enabled=true");
        }

        Duration duration = parseDuration(schedulerProps.getDuration());
        Duration period   = parseDuration(schedulerProps.getInterval());
        totalPlanned = computeTotalPlanned(duration, period);
        sent.set(0);
        startedAt = Instant.now();
        endsAt    = startedAt.plus(duration);

        pregenFiles = preGenerateAll(totalPlanned, period);
        log.info("Pre-generated {} files into {}", totalPlanned, outputProps.getDir());

        if (sending) {
            future = scheduler.scheduleAtFixedRate(new Sender(), period);
            running = true;
            log.info("Sending enabled: scheduling sender every {}", period);
        } else {
            running = false; // generation-only mode
            log.info("Sending disabled: generation complete, no HTTP posts will be made.");
        }

        return status();
    }
    public synchronized Status stop() {
        if (future != null) future.cancel(false);
        running = false;
        return status();
    }

    public Status status() {
        return new Status(running, sent.get(), totalPlanned,
                targetProps.getEndpoint(), startedAt, endsAt);
    }

    /** One-shot: generate all files immediately, no scheduling, no sending. */
    public synchronized int generateAllNow() {
        Objects.requireNonNull(sampleProps.getPath(), "sample.path is required");
        Objects.requireNonNull(outputProps.getDir(), "output.dir is required");

        Duration duration = parseDuration(schedulerProps.getDuration());
        Duration period   = parseDuration(schedulerProps.getInterval());
        int planned = computeTotalPlanned(duration, period);

        pregenFiles = preGenerateAll(planned, period);
        totalPlanned = planned;
        sent.set(0);
        running = false;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        log.info("Pre-generated {} campaign payloads into {}", planned, outputProps.getDir());
        return planned;
    }

    private List<Path> preGenerateAll(int N, Duration period) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Path samplePath = Paths.get(sampleProps.getPath());
            String json = Files.readString(samplePath);
            @SuppressWarnings("unchecked")
            Map<String,Object> sample = mapper.readValue(json, Map.class);

            Path outDir = Paths.get(outputProps.getDir());
            Files.createDirectories(outDir);

            return IntStream.rangeClosed(1, N)
                    .mapToObj(t -> {
                        try {
                            OffsetDateTime scheduledLocal =
                                    OffsetDateTime.now(PHOENIX).plusSeconds(period.getSeconds() * (long)(t - 1));
                            String outageId = "outage-" + scheduledLocal.format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssXXX"));
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
    private Map<String, Object> generateFromSample(Map<String, Object> sample, int t, int N, String baseId) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Deep copy the whole sample to preserve structure
        Map<String, Object> root = mapper.readValue(mapper.writeValueAsBytes(sample), Map.class);

        // Expect "outages" array in the sample
        List<Map<String, Object>> sampleOutages = (List<Map<String, Object>>) root.get("outages");
        if (sampleOutages == null || sampleOutages.isEmpty()) {
            throw new IllegalStateException("Sample must contain 'outages' array with at least one element.");
        }

        // Template for a single outage
        Map<String, Object> outageTemplate = sampleOutages.get(0);
        List<Map<String, Object>> affectedTemplate =
                (List<Map<String, Object>>) outageTemplate.getOrDefault("affectedDeliveryNodes", new ArrayList<>());

        // Ramp and control
        double r = ramp(t, N,
                schedulerProps.getRamp().getShape(),
                schedulerProps.getRamp().getA(),
                schedulerProps.getRamp().getK());

        int maxOutagesTotal = schedulerProps.getMaxOutagesTotal();
        int outagesThisFile = Math.max(1, (int) Math.round(maxOutagesTotal * (1.0 / N)));

        int avgNodes = Math.max(1, schedulerProps.getAvgNodesPerFile());
        int nodesThisFile = Math.max(1, (int) Math.round(avgNodes * (0.5 + r)));

        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);

        // Build outages array
        List<Map<String, Object>> newOutages = new ArrayList<>();
        for (int j = 1; j <= outagesThisFile; j++) {
            // Deep copy outage template
            Map<String, Object> outage = mapper.readValue(mapper.writeValueAsBytes(outageTemplate), Map.class);

            // Unique outage ID
            String outageId = baseId + "-" + String.format("%02d", j);
            outage.put("id", outageId);

            // Set/update timestamps
            OffsetDateTime startedAt = nowUtc.minusMinutes(5L * j);
            OffsetDateTime updatedAt = nowUtc;
            OffsetDateTime etr = nowUtc.plusMinutes(30L + 5L * j);
            outage.put("startedAt", startedAt.toString());
            outage.put("updatedAt", updatedAt.toString());
            outage.put("etr", etr.toString());

            // Generate affectedDeliveryNodes: one dnId == one customer
            Map<String, Object> nodeTemplate = affectedTemplate.isEmpty()
                    ? Map.of("dnId", "0")
                    : affectedTemplate.get(0);

            List<Map<String, Object>> nodes = new ArrayList<>(nodesThisFile);
            int dnStart = (t * 100000) + (j * 1000); // unique-ish per file/outage
            for (int i = 1; i <= nodesThisFile; i++) {
                Map<String, Object> n = mapper.readValue(mapper.writeValueAsBytes(nodeTemplate), Map.class);
                n.put("dnId", String.valueOf(dnStart + i)); // numeric string
                nodes.add(n);
            }
            outage.put("affectedDeliveryNodes", nodes);

            newOutages.add(outage);
        }

        // Replace outages array and remove any extra top-level fields
        root.put("outages", newOutages);

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

        String type = Optional.ofNullable(targetProps.getAuth())
                .map(TargetProperties.Auth::getType).orElse("none");

        if ("bearer".equalsIgnoreCase(type) && targetProps.getAuth().getToken() != null) {
            req = req.header("Authorization", "Bearer " + targetProps.getAuth().getToken());
        } else if ("header".equalsIgnoreCase(type) && targetProps.getAuth().getHeaderName() != null) {
            req = req.header(targetProps.getAuth().getHeaderName(), targetProps.getAuth().getHeaderValue());
        }

        req.bodyValue(payloadJson)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(Retry.backoff(retryProps.getMaxAttempts(),
                        Duration.ofSeconds(retryProps.getBackoffSeconds()))
                        .maxBackoff(Duration.ofSeconds(30)))
                .block();
    }

    private static double ramp(int t, int N, String shape, double a, double k) {
        double x = Math.max(0, Math.min(1, (double) t / (double) N));
        switch (shape.toLowerCase()) {
            case "linear": return x;
            case "exp":
                double ek = Math.expm1(k);
                return Math.expm1(k * x) / (ek == 0 ? 1 : ek);
            default: // sigmoid
                double s  = 1.0 / (1.0 + Math.exp(-a * (x - 0.5)));
                double s0 = 1.0 / (1.0 + Math.exp(-a * (0 - 0.5)));
                double s1 = 1.0 / (1.0 + Math.exp(-a * (1 - 0.5)));
                return (s - s0) / (s1 - s0);
        }
    }

    private static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        if (s.endsWith("s"))  return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("m"))  return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("h"))  return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("d"))  return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
        return Duration.parse(s);
    }

    private static int computeTotalPlanned(Duration duration, Duration period) {
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("scheduler.interval must be greater than zero");
        }
        double ratio = (double) duration.toNanos() / (double) period.toNanos();
        double bounded = Math.max(1.0d, ratio);
        return (int) Math.ceil(bounded);
    }
}
