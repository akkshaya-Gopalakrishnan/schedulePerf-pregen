package com.example.outages;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/campaign")
public class CampaignController {

    private final CampaignService service;

    public CampaignController(CampaignService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<CampaignService.Status> start() {
        return ResponseEntity.ok(service.start());
    }

    @GetMapping("/status")
    public ResponseEntity<CampaignService.Status> status() {
        return ResponseEntity.ok(service.status());
    }

    @PostMapping("/stop")
    public ResponseEntity<CampaignService.Status> stop() {
        return ResponseEntity.ok(service.stop());
    }
}
