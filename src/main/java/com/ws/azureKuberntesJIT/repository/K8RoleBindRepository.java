package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8RoleBind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface K8RoleBindRepository extends JpaRepository<K8RoleBind, Long> {
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);
}
