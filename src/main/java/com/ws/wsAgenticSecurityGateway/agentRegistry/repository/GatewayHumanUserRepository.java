package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayHumanUserRepository extends JpaRepository<GatewayHumanUserEntity, UUID> {

    Optional<GatewayHumanUserEntity> findByIdpSubject(String idpSubject);

    Optional<GatewayHumanUserEntity> findByPreferredUsername(String preferredUsername);

    List<GatewayHumanUserEntity> findByStatus(String status);

    Optional<GatewayHumanUserEntity> findByEmail(String email);

    List<GatewayHumanUserEntity> findByPreferredUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String email);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.lastSeenAt > :since")
    long countActiveHumansSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.status = 'BLOCKED'")
    long countBlocked();

    /** Atomic request count increment — called async on every orchestration for human-delegated sessions. */
    @Modifying
    @Query("UPDATE GatewayHumanUserEntity h SET h.totalRequests = h.totalRequests + 1, " +
            "h.lastSeenAt = CURRENT_TIMESTAMP WHERE h.id = :humanUserId")
    void incrementRequestCount(@Param("humanUserId") UUID humanUserId);
}
