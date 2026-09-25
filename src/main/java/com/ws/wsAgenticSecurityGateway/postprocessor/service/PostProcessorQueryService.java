package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationSummary;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Classifications for the current tenant, newest first. A {@code correlationId} / {@code traceId} is the "jump"
     * shortcut (one hop / one journey) and wins if present; otherwise the multi-select facets apply — sensitivities,
     * categories, capability types, protocols, producers — combined AND across facets, OR within a facet. No facet ⇒
     * the recent list (page-limited).
     */
    public List<ClassificationView> list(String correlationId, String traceId,
                                         List<String> sensitivities, List<String> categories,
                                         List<String> capabilityTypes, List<String> protocols,
                                         List<String> producers, int limit) {
        String tenant = TenantContext.get();
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<GatewayResponseClassificationEntity> rows;

        if (isSet(correlationId)) {
            rows = repository.findByWsTenantNameAndCorrelationId(tenant, correlationId.trim());
        } else if (isSet(traceId)) {
            rows = repository.findByWsTenantNameAndTraceId(tenant, traceId.trim());
        } else {
            rows = repository.search(tenant, csv(sensitivities), csv(capabilityTypes), csv(protocols),
                    csv(producers), csv(categories), capped);
        }

        return rows.stream()
                .sorted(Comparator.comparing(GatewayResponseClassificationEntity::getClassifiedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(ClassificationView::from)
                .toList();
    }

    /** The filter dropdowns' choices, served from the backend (distinct values the tenant actually has). */
    public Map<String, List<String>> filterOptions() {
        String tenant = TenantContext.get();
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("sensitivities", repository.distinctSensitivities(tenant));
        out.put("categories", repository.distinctCategories(tenant));
        out.put("capabilityTypes", repository.distinctCapabilityTypes(tenant));
        out.put("protocols", repository.distinctProtocols(tenant));
        out.put("producers", repository.distinctProducers(tenant));
        return out;
    }

    /** Join a facet's selected values with {@code \n} for the native query, or null when nothing is selected. */
    private static String csv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> clean = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return clean.isEmpty() ? null : String.join("\n", clean);
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
