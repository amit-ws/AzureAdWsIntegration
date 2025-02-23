package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8RoleReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface K8RoleReferenceRepository extends JpaRepository<K8RoleReference, Long> {
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);
}
