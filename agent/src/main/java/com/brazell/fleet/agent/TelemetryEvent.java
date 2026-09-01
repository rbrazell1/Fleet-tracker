package com.brazell.fleet.agent;

public record TelemetryEvent(
    Long seq,
    String type,
    long deviceUptimeMs,
    long receivedAtEpochMs,
    String payloadJson
    ) {}