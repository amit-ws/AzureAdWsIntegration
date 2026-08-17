package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationSummary;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read side of the post-processor: serves the Processed-Data view. Tenant-scoped (via {@link TenantContext}, set
 * for the admin request thread) and read-only — this never mutates classifications. Filtering picks the most
 * specific key supplied so the same endpoint powers the jump links (correlation from an audit event, trace from a
 * chain/DAG view) and the default recent list.
 */
@Service
public class PostProcessorQueryService {

    /** Default page size for the recent list; capped so a broad query can't pull the whole table. */
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final GatewayResponseClassificationRepository repository;

    public PostProcessorQueryService(GatewayResponseClassificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Classifications for the current tenant, newest first. Applies the most specific filter provided:
     * correlationId → traceId → producer → sensitivity; otherwise the recent list (page-limited).
     */
    public List<ClassificationView> list(String correlationId, String traceId, String sensitivity,
                                         String producer, int limit) {
        String tenant = TenantContext.get();
        List<GatewayResponseClassificationEntity> rows;

        if (isSet(correlationId)) {
            rows = repository.findByWsTenantNameAndCorrelationId(tenant, correlationId.trim());
        } else if (isSet(traceId)) {
            rows = repository.findByWsTenantNameAndTraceId(tenant, traceId.trim());
        } else if (isSet(producer)) {
            rows = repository.findByWsTenantNameAndProducer(tenant, producer.trim());
        } else if (isSet(sensitivity)) {
            rows = repository.findByWsTenantNameAndSensitivity(tenant, sensitivity.trim().toUpperCase(Locale.ROOT));
        } else {
            int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            rows = repository.findByWsTenantNameOrderByClassifiedAtDesc(tenant, PageRequest.of(0, capped));
        }

        return rows.stream()
                .sorted(Comparator.comparing(GatewayResponseClassificationEntity::getClassifiedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(ClassificationView::from)
                .toList();
    }

    /** Accurate header counts for the current tenant (computed SQL-side, not from a capped list). */
    public ClassificationSummary summary() {
        String tenant = TenantContext.get();
        long total = repository.countByWsTenantName(tenant);
        long injection = repository.countByWsTenantNameAndInjectionDetectedTrue(tenant);

        Map<String, Long> bySensitivity = new LinkedHashMap<>();
        for (Object[] row : repository.sensitivityBreakdown(tenant)) {
            String label = row[0] == null ? "UNKNOWN" : String.valueOf(row[0]);
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            bySensitivity.put(label, count);
        }
        return new ClassificationSummary(total, bySensitivity, injection);
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
