package com.example.outages;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/campaigns")
public class CampaignController {

    private final CampaignService service;

    public CampaignController(CampaignService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<CampaignService.Status> start() {
        return ResponseEntity.ok(service.start());
    }

    @PostMapping("/stop")
    public ResponseEntity<CampaignService.Status> stop() {
        return ResponseEntity.ok(service.stop());
    }

    @GetMapping("/status")
    public ResponseEntity<CampaignService.Status> status() {
        return ResponseEntity.ok(service.status());
    }
}
