package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayHumanUserRepository extends JpaRepository<GatewayHumanUserEntity, UUID> {

    Optional<GatewayHumanUserEntity> findByIdpSubject(String idpSubject);

    Optional<GatewayHumanUserEntity> findByPreferredUsername(String preferredUsername);

    List<GatewayHumanUserEntity> findByStatus(String status);

    Optional<GatewayHumanUserEntity> findByEmail(String email);
}
