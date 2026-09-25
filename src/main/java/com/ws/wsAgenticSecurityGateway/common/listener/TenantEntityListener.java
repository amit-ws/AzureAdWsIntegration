package com.ws.wsAgenticSecurityGateway.common.listener;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import jakarta.persistence.PrePersist;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global JPA entity listener that stamps the current tenant onto any entity declaring a
 * {@code wsTenantName} field, when that field is null at insert time.
 *
 * <p>Registered as a <b>default entity listener</b> via {@code META-INF/orm.xml}, so it applies
 * to every entity automatically — no per-entity annotations, no per-service code. This is the
 * single, secure place to guarantee tenant stamping: the value always comes from
 * {@link TenantContext} (populated server-side from the {@code X-WS-Tenant} header), never from
 * the request body.
 *
 * <p>Safety — it cannot break an existing working path:
 * <ul>
 *   <li>Only fills a <b>null</b> {@code wsTenantName}; never overwrites a value a service already set.</li>
 *   <li>Only acts when a tenant is present in context (e.g. an HTTP request carrying the header);
 *       background/async inserts with no tenant context are left untouched.</li>
 *   <li>Never sets null and never throws — reflection issues are swallowed so a persist is never blocked.</li>
 * </ul>
 */
public class TenantEntityListener {

    /** Per-class cache of the resolved {@code wsTenantName} field (empty = entity has no such field). */
    private static final Map<Class<?>, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    @PrePersist
    public void stampTenant(Object entity) {
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) {
            return; // no tenant in context (e.g. background insert) — leave the entity as-is
        }
        Optional<Field> field = FIELD_CACHE.computeIfAbsent(entity.getClass(), TenantEntityListener::findTenantField);
        if (field.isEmpty()) {
            return; // entity is not tenant-scoped
        }
        try {
            Field f = field.get();
            if (f.get(entity) == null) {
                f.set(entity, tenant);
            }
        } catch (IllegalAccessException ignored) {
            // Never block a persist over a reflection issue.
        }
    }

    private static Optional<Field> findTenantField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("wsTenantName");
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException ignored) {
                // walk up the hierarchy
            }
        }
        return Optional.empty();
    }
}
