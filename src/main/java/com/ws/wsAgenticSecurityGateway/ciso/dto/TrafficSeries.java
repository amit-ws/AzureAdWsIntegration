package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The CISO → Dashboard traffic time-series (#70) — governed traffic bucketed over the window. Beyond the reference's
 * three lines (requests / sensitive / denied) we also carry the MCP vs A2A split and the allow count, since the
 * gateway sees all of it — the FE can plot the core three and offer the rest. Every point is a real bucket count;
 * empty buckets are zero-filled so the line stays continuous.
 */
public record TrafficSeries(
        String window,
        String bucket,          // the date_trunc granularity used ("hour" | "day")
        List<TrafficPoint> points) {

    public record TrafficPoint(
            LocalDateTime t,
            long requests,      // classified responses through the gateway in this bucket
            long mcp,
            long a2a,
            long sensitive,     // Confidential + Restricted
            long allowed,       // PDP allow
            long denied) {}     // PDP deny
}
