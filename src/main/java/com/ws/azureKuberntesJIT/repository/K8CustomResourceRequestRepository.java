package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8CustomResourceRequest;
import com.ws.azureKuberntesJIT.enttity.K8ResourceRequest;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface K8CustomResourceRequestRepository extends JpaRepository<K8CustomResourceRequest, UUID> {
    List<K8CustomResourceRequest> findAllByStatus(RequestStatus status);


}
