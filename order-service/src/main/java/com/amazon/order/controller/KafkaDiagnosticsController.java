package com.amazon.order.controller;


import com.amazon.order.diagnostics.KafkaProducerDiagnostics;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/test/kafka")
@Profile("test")
public class KafkaDiagnosticsController {

    private final KafkaProducerDiagnostics diagnostics;

    public KafkaDiagnosticsController(
            KafkaProducerDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/producer")
    public Map<String, Object> getProducerDiagnostics() {
        return diagnostics.getDiagnostics();
    }
}
