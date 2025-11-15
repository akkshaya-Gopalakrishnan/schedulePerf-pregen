# Map Outage Scheduler — EC2 WAR (Pre-generate + Send)

## What changed (per your request)
- **Sample JSON** is now read from a **local folder** in the app/server; path is configurable in YAML (`sample.path`).
- On **start**, the app **pre-generates ALL files** for the full duration (e.g., 24 files for 4h @ 10m) into `output.dir`.
- A scheduled sender then **picks one file every interval** from that folder (in order) and POSTs to your datafeed API.

## Configure
Edit `/etc/map-outage-scheduler/application.yml` on EC2:
```yaml
scheduler:
  interval: 10m
  duration: 4h
  maxOutagesTotal: 500
  maxReachPctByMaxHour: 0.6
  avgNodesPerFile: 120
  ramp: { shape: sigmoid, a: 10, k: 2.5 }

sample:
  path: /opt/map-outage-scheduler/sample/sample.json   # put your sample here

output:
  dir: /var/lib/map-outage-scheduler/outbox            # all pre-generated files land here

target:
  endpoint: https://YOUR_DATAFEED_URL
  auth: { type: bearer, token: YOUR_TOKEN }

retry: { maxAttempts: 5, backoffSeconds: 1 }
```

## Deploy
- Build the WAR: `mvn -q -DskipTests package`
- Copy to Tomcat: `/var/lib/tomcat/webapps/ROOT.war`
- External config: add to Tomcat `JAVA_OPTS`: `-Dspring.config.additional-location=file:/etc/map-outage-scheduler/application.yml`
- Create folders:
  - `/opt/map-outage-scheduler/sample/sample.json` (your sample)
  - `/var/lib/map-outage-scheduler/outbox` (pre-gen output)

## Use
```
curl -X POST http://<ec2-ip>:8080/campaigns/start
curl     http://<ec2-ip>:8080/campaigns/status
curl -X POST http://<ec2-ip>:8080/campaigns/stop
```

## Notes
- Files are named `NNN-outage-YYYY-MM-DDTHH-mm-ss±hh:mm.json` so they sort naturally.
- If you restart Tomcat during a campaign, you can simply call `/campaigns/start` again to re-generate and resume a fresh run.
- Fine-tune the generator to your exact schema by editing `CampaignService.generateFromSample(...)` once you share your real sample.
