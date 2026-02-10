package com.ws.certificateJIT.k8;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface JitAccessGrantRepository extends JpaRepository<JitAccessGrant, Long> {

    @Query("SELECT g FROM JitAccessGrant g WHERE g.expiresAt IS NOT NULL AND g.expiresAt < :instant")
    List<JitAccessGrant> findExpiringAfter(@Param("instant") Instant instant);

    @Query("select g from JitAccessGrant g where g.rotatedAt is not null and g.rotatedAt < :instant")
    List<JitAccessGrant> findAllRotatedBefore(@Param("instant") Instant cutoff);

}
