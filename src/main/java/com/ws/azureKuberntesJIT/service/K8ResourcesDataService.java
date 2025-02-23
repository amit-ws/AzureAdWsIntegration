package com.ws.azureKuberntesJIT.service;

import com.ws.azureKuberntesJIT.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class K8ResourcesDataService {
    final K8NamespaceRepository k8NamespaceRepository;
    final K8StorageClassRepository k8StorageClassRepository;
    final K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository;
    final K8PersistentVolumeRepository k8PersistentVolumeRepository;
    final K8NodeRepository k8NodeRepository;
    final K8DeploymentRepository k8DeploymentRepository;
    final K8ServiceAccountRepository k8ServiceAccountRepository;
    final K8SecretRepository k8SecretRepository;
    final K8ConfigMapRepository k8ConfigMapRepository;
    final K8NetworkPolicyRepository k8NetworkPolicyRepository;
    final K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository;
    final K8RoleRepository k8RoleRepository;
    final K8RoleBindRepository k8RoleBindRepository;
    final K8RoleReferenceRepository k8RoleReferenceRepository;


    @Autowired
    public K8ResourcesDataService(K8NamespaceRepository k8NamespaceRepository, K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository,
                                  K8StorageClassRepository k8StorageClassRepository, K8PersistentVolumeRepository k8PersistentVolumeRepository,
                                  K8NodeRepository k8NodeRepository, K8DeploymentRepository k8DeploymentRepository,
                                  K8ServiceAccountRepository k8ServiceAccountRepository, K8SecretRepository k8SecretRepository,
                                  K8ConfigMapRepository k8ConfigMapRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository, K8RoleRepository k8RoleRepository,
                                  K8RoleBindRepository k8RoleBindRepository, K8RoleReferenceRepository k8RoleReferenceRepository) {
        this.k8NamespaceRepository = k8NamespaceRepository;
        this.k8CustomResourceDefinitionRepository = k8CustomResourceDefinitionRepository;
        this.k8StorageClassRepository = k8StorageClassRepository;
        this.k8PersistentVolumeRepository = k8PersistentVolumeRepository;
        this.k8NodeRepository = k8NodeRepository;
        this.k8DeploymentRepository = k8DeploymentRepository;
        this.k8ServiceAccountRepository = k8ServiceAccountRepository;
        this.k8SecretRepository = k8SecretRepository;
        this.k8ConfigMapRepository = k8ConfigMapRepository;
        this.k8NetworkPolicyRepository = k8NetworkPolicyRepository;
        this.k8PersistentVolumeClaimRepository = k8PersistentVolumeClaimRepository;
        this.k8RoleRepository = k8RoleRepository;
        this.k8RoleBindRepository = k8RoleBindRepository;
        this.k8RoleReferenceRepository = k8RoleReferenceRepository;
    }


    public void deleteK8ResourcesByWsTenantName(String wsTenantName) {
        executeDelete(wsTenantName);
    }

    private void executeDelete(String wsTenantName) {
        k8NamespaceRepository.deleteAllByWsTenantName(wsTenantName);
        k8StorageClassRepository.deleteAllByWsTenantName(wsTenantName);
        k8CustomResourceDefinitionRepository.deleteAllByWsTenantName(wsTenantName);
        k8PersistentVolumeRepository.deleteAllByWsTenantName(wsTenantName);
        k8NodeRepository.deleteAllByWsTenantName(wsTenantName);
        k8DeploymentRepository.deleteAllByWsTenantName(wsTenantName);
        k8ServiceAccountRepository.deleteAllByWsTenantName(wsTenantName);
        k8SecretRepository.deleteAllByWsTenantName(wsTenantName);
        k8ConfigMapRepository.deleteAllByWsTenantName(wsTenantName);
        k8NetworkPolicyRepository.deleteAllByWsTenantName(wsTenantName);
        k8PersistentVolumeClaimRepository.deleteAllByWsTenantName(wsTenantName);
        k8RoleRepository.deleteAllByWsTenantName(wsTenantName);
        k8RoleBindRepository.deleteAllByWsTenantName(wsTenantName);
        k8RoleReferenceRepository.deleteAllByWsTenantName(wsTenantName);
    }


}
