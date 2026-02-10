package com.ws.azureKuberntesJIT.service;

import com.ws.azureAdIntegration.exception.K8ResourceException;
import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
import com.ws.azureKuberntesJIT.constant.K8RoleKind;
import com.ws.azureKuberntesJIT.constant.K8SubjectKind;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
@Service
public class K8ClientService {
    RbacAuthorizationV1Api rbacApi;

    public RbacAuthorizationV1Api initializeK8ClientWithRbacApi(String clusterServerURL, String token) {
        initializeK8Client(clusterServerURL, token);
        return initializeK8RbacClientAndGet();
    }



    public ApiClient initializeK8Client(String clusterServerURL, String token) {
        try {
            if (StringUtils.isEmpty(clusterServerURL)) {
                throw new K8ResourceException("Cluster Server URL is required to initialize K8 clients");
            }
            if (StringUtils.isEmpty(token)) {
                throw new K8ResourceException("Cluster server token is required to initialize K8 clients");
            }
            ApiClient client = Config.fromToken(clusterServerURL, token);
            client.setVerifyingSsl(false);
            Configuration.setDefaultApiClient(client);
            return client;
        } catch (Exception ex) {
            log.error("Error in initializing k8 client");
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }


    public void initializeK8RbacClient() {
        this.rbacApi = new RbacAuthorizationV1Api();
    }

    public void initializeK8RbacClient(RbacAuthorizationV1Api rbacApi) {
        this.rbacApi = rbacApi;
    }

    public RbacAuthorizationV1Api initializeK8RbacClientAndGet() {
        return new RbacAuthorizationV1Api();
    }


    public V1Role createNamespaceRole(String namespace, String roleName, String resourceName, List<String> verbs, String apiGroup, String resources) {
        try {
            checkIfRbacApiIsNull();
            V1Role v1Role = new V1Role()
                    .metadata(new V1ObjectMeta().name(roleName).namespace(namespace))
                    .rules(Collections.singletonList(
                            new V1PolicyRule()
                                    .verbs(verbs)
                                    .apiGroups(Collections.singletonList(apiGroup))
                                    .resources(Collections.singletonList(resources))
                                    .resourceNames(Collections.singletonList(resourceName))
                    ));
            v1Role = rbacApi.createNamespacedRole(namespace, v1Role).execute();
            log.info("created namespace role id: {}", v1Role.getMetadata().getUid());
            return v1Role;
        } catch (Exception exp) {
            if (exp.getMessage().contains("409")) {
                throw new K8ResourceException("Namespace role already exists in your kubernetes cluster with the name: " + "");
            }
            throw new K8ResourceException("Error while creating namespace role " + exp.getMessage());
        }
    }

    public V1ClusterRole createClusterRole(String roleName, String resourceName, List<String> verbs, String apiGroup, String resources) {
        try {
            checkIfRbacApiIsNull();
            V1ClusterRole v1ClusterRole = new V1ClusterRole()
                    .metadata(new V1ObjectMeta().name(roleName))
                    .rules(Collections.singletonList(
                            new V1PolicyRule()
                                    .verbs(verbs)
                                    .apiGroups(Collections.singletonList(apiGroup))
                                    .resources(Collections.singletonList(resources))
                                    .resourceNames(Collections.singletonList(resourceName))
                    ));
            return rbacApi.createClusterRole(v1ClusterRole).execute();
        } catch (Exception exp) {
            if (exp.getMessage().contains("409")) {
                throw new K8ResourceException("ClusterRole already exists in your Kubernetes cluster with the name: " + roleName);
            }
            throw new K8ResourceException("Error while creating ClusterRole: " + exp.getMessage());
        }
    }


    public V1RoleBinding createNamespaceRoleBinding(String namespace,
                                                    String roleBindingName,
                                                    String roleName,
                                                    String userName,
                                                    String apiGroup,
                                                    String apiVersion) {
        try {
            checkIfRbacApiIsNull();
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(roleBindingName);
            metadata.setNamespace(namespace);

            V1RoleRef roleRef = new V1RoleRef();
            roleRef.setKind(K8RoleKind.Role.name());
            roleRef.setName(roleName);
            roleRef.setApiGroup(apiGroup);

            RbacV1Subject subject = new RbacV1Subject();
            subject.setKind(K8SubjectKind.USER.getKind());
            subject.setName(userName);
            subject.setApiGroup(apiGroup);

            V1RoleBinding roleBinding = new V1RoleBinding();
            roleBinding.setMetadata(metadata);
            roleBinding.setRoleRef(roleRef);
            roleBinding.addSubjectsItem(subject);
            roleBinding.setKind(K8RoleBindingType.RoleBinding.name());
            roleBinding.setApiVersion(apiVersion);

            roleBinding = this.rbacApi.createNamespacedRoleBinding(namespace, roleBinding).execute();
            log.info("Created namespaceRoleBinding UID: {}", roleBinding.getMetadata().getUid());
            return roleBinding;
        } catch (ApiException exp) {
            throw new K8ResourceException("Failed to assign namespace role. Message: " + exp.getMessage());
        }
    }


    public V1ClusterRoleBinding createClusterRoleBinding(String clusterRoleName,
                                                         String roleBindingName,
                                                         String userName,
                                                         String apiGroup,
                                                         String apiVersion) {
        try {
            checkIfRbacApiIsNull();
            V1ObjectMeta metadata = new V1ObjectMeta();
            metadata.setName(roleBindingName);
            metadata.setNamespace(null);

            V1RoleRef roleRef = new V1RoleRef();
            roleRef.setKind(K8RoleKind.ClusterRole.name());
            roleRef.setName(clusterRoleName);
            roleRef.setApiGroup(apiGroup);

            RbacV1Subject subject = new RbacV1Subject();
            subject.setKind(K8SubjectKind.USER.getKind());
            subject.setName(userName);
            subject.setApiGroup(apiGroup);

            V1ClusterRoleBinding clusterRoleBinding = new V1ClusterRoleBinding();
            clusterRoleBinding.setMetadata(metadata);
            clusterRoleBinding.setRoleRef(roleRef);
            clusterRoleBinding.setSubjects(Collections.singletonList(subject));
            clusterRoleBinding.setKind(K8RoleBindingType.ClusterRoleBinding.name());
            clusterRoleBinding.setApiVersion(apiVersion);

            clusterRoleBinding = this.rbacApi.createClusterRoleBinding(clusterRoleBinding).execute();
            log.info("Created clusterRoleBinding UID: {}", clusterRoleBinding.getMetadata().getUid());
            return clusterRoleBinding;
        } catch (ApiException exp) {
            throw new K8ResourceException("Failed to assign cluster role. Message: " + exp.getMessage());
        }
    }


    public void revokeClusterRoleBinding(String name) {
        try {
            checkIfRbacApiIsNull();
            this.rbacApi.deleteClusterRoleBinding(name).execute();
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.warn(String.format("No ClusterRoleBinding found for provided binding name: %s", name));
            }
            log.error("Failed to delete cluster role binding. Message: " + exp.getMessage());
        }
    }

    public void revokeNamespaceRoleBinding(String namespace, String name) {
        try {
            checkIfRbacApiIsNull();
            this.rbacApi.deleteNamespacedRoleBinding(name, namespace).execute();
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.warn(String.format("No RoleBinding found for provided binding name: %s of namespace: %s", name, namespace));
            }
            log.error("Failed to delete cluster role binding. Message: " + exp.getMessage());
        }
    }

    public void revokeNamespaceRole(String namespace, String name) {
        try {
            checkIfRbacApiIsNull();
            this.rbacApi.deleteNamespacedRole(name, namespace).execute();
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.warn(String.format("No namespace scoped role found for provided role name: %s of namespace: %s", name, namespace));
            }
            log.error("Failed to delete cluster role. Message: " + exp.getMessage());
        }
    }

    public void revokeClusterRole(String name) {
        try {
            checkIfRbacApiIsNull();
            this.rbacApi.deleteClusterRole(name).execute();
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.warn(String.format("No cluster scoped role found for provided role name: %s of namespace: %s", name));
            }
            log.error("Failed to delete cluster role. Message: " + exp.getMessage());
        }
    }


    private void checkIfRbacApiIsNull() {
        if (ObjectUtils.isEmpty(this.rbacApi)) {
            throw new K8ResourceException("RbacAuthorizationV1Api must be initialized before calling K8 rbac api");
        }
    }
}
