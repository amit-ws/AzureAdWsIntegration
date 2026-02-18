package com.ws.azureKuberntesJIT.service;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Role;
import com.ws.azureKuberntesJIT.enttity.K8RoleBind;
import com.ws.azureKuberntesJIT.enttity.K8RoleReference;
import com.ws.azureKuberntesJIT.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

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
    final K8JobRepository k8JobRepository;
    final K8CronJobRepository k8CronJobRepository;
    final K8IngressRepository k8IngressRepository;
    final K8ServiceRepository k8ServiceRepository;
    final K8DaemonSetRepository k8DaemonSetRepository;
    final K8StatefulSetRepository k8StatefulSetRepository;
    final K8ReplicaSetRepository k8ReplicaSetRepository;


    @Autowired
    public K8ResourcesDataService(K8NamespaceRepository k8NamespaceRepository, K8CustomResourceDefinitionRepository k8CustomResourceDefinitionRepository,
                                  K8StorageClassRepository k8StorageClassRepository, K8PersistentVolumeRepository k8PersistentVolumeRepository,
                                  K8NodeRepository k8NodeRepository, K8DeploymentRepository k8DeploymentRepository,
                                  K8ServiceAccountRepository k8ServiceAccountRepository, K8SecretRepository k8SecretRepository,
                                  K8ConfigMapRepository k8ConfigMapRepository, K8NetworkPolicyRepository k8NetworkPolicyRepository,
                                  K8PersistentVolumeClaimRepository k8PersistentVolumeClaimRepository, K8RoleRepository k8RoleRepository,
                                  K8RoleBindRepository k8RoleBindRepository, K8RoleReferenceRepository k8RoleReferenceRepository, K8JobRepository k8JobRepository, K8CronJobRepository k8CronJobRepository, K8IngressRepository k8IngressRepository, K8ServiceRepository k8ServiceRepository, K8DaemonSetRepository k8DaemonSetRepository, K8StatefulSetRepository k8StatefulSetRepository, K8ReplicaSetRepository k8ReplicaSetRepository) {
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
        this.k8JobRepository = k8JobRepository;
        this.k8CronJobRepository = k8CronJobRepository;
        this.k8IngressRepository = k8IngressRepository;
        this.k8ServiceRepository = k8ServiceRepository;
        this.k8DaemonSetRepository = k8DaemonSetRepository;
        this.k8StatefulSetRepository = k8StatefulSetRepository;
        this.k8ReplicaSetRepository = k8ReplicaSetRepository;
    }


    public void deleteByWsTenantNameAndSubscriptionIds(String wsTenantName, CloudProviderType cloudProviderType, Collection<String> cloudResourceAccountIds) {
        executeDelete(wsTenantName, cloudProviderType, cloudResourceAccountIds);
    }


    public void deleteK8RolesAndBindings(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudResourceAccountIds) {
        k8RoleRepository.deleteAll(findAllK8RoleByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
        k8RoleReferenceRepository.deleteAll(findAllK8RoleRefByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
        k8RoleBindRepository.deleteAll(findAllK8RoleBindingByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
    }


    private void executeDelete(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudResourceAccountIds) {
        k8NamespaceRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8StorageClassRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8CustomResourceDefinitionRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8PersistentVolumeRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8NodeRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8DeploymentRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8ServiceAccountRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8SecretRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8ConfigMapRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8NetworkPolicyRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8PersistentVolumeClaimRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);

        k8RoleRepository.deleteAll(findAllK8RoleByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
        k8RoleReferenceRepository.deleteAll(findAllK8RoleRefByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
        k8RoleBindRepository.deleteAll(findAllK8RoleBindingByWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds));
//        k8RoleRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
//        k8RoleBindRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
//        k8RoleReferenceRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);

        k8JobRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8CronJobRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8IngressRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8DaemonSetRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8ReplicaSetRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8StatefulSetRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
        k8ServiceRepository.deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);

    }


    private List<K8Role> findAllK8RoleByWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudResourceAccountIds) {
        return k8RoleRepository.findAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
    }


    private List<K8RoleReference> findAllK8RoleRefByWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudResourceAccountIds) {
        return k8RoleReferenceRepository.findAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
    }


    private List<K8RoleBind> findAllK8RoleBindingByWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudResourceAccountIds) {
        return k8RoleBindRepository.findAllUsingWsTenantNameAndCloudTypeAndCloudIds(wsTenantName, cloudType, cloudResourceAccountIds);
    }


}
