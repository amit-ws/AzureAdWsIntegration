package com.ws.certificateJIT.k8;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class RoleBindingService {

    private final ApiClient apiClient;

    @Autowired
    public RoleBindingService(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public String createRole(String namespace, String resource, List<String> verbs) throws Exception {
        RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api(apiClient);

        log.info("Creating Role in namespace: {} for resource: {}",
                namespace, resource);

        V1Role v1Role = new V1Role()
                .metadata(new V1ObjectMeta().name("secret-reader-" + UUID.randomUUID()).namespace(namespace))
                .rules(Collections.singletonList(
                        new V1PolicyRule()
                                .verbs(verbs)
                                .apiGroups(Collections.singletonList(""))
                                .resources(Collections.singletonList("secrets"))
                                .resourceNames(Collections.singletonList(resource))
                ));
        v1Role = rbacApi.createNamespacedRole(namespace, v1Role).execute();
        return v1Role.getMetadata().getName();
    }

    /**
     * Create RoleBinding to bind Role to certificate's CN
     */
    public void createRoleBinding(
            String namespace,
            String roleName,
            String userName,
            String roleBindingName) throws Exception {

        log.info("Creating RoleBinding: {} in namespace: {} for user: {}",
                roleBindingName, namespace, userName);

        RbacAuthorizationV1Api api = new RbacAuthorizationV1Api(apiClient);

        V1RoleBinding roleBinding = new V1RoleBinding();
        roleBinding.setApiVersion("rbac.authorization.k8s.io/v1");
        roleBinding.setKind("RoleBinding");

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(roleBindingName);
        metadata.setNamespace(namespace);
        roleBinding.setMetadata(metadata);

        V1RoleRef roleRef = new V1RoleRef();
        roleRef.setApiGroup("rbac.authorization.k8s.io");
        roleRef.setKind("Role");
        roleRef.setName(roleName);
        roleBinding.setRoleRef(roleRef);

        RbacV1Subject subject = new RbacV1Subject();
        subject.setKind("User");
        subject.setName(userName);
        subject.setApiGroup("rbac.authorization.k8s.io");

        roleBinding.setSubjects(Arrays.asList(subject));

        api.createNamespacedRoleBinding(namespace, roleBinding).execute();

        log.info("RoleBinding created: {}", roleBindingName);
    }

    /**
     * Delete Role
     */
    public void deleRole(String namespace, String roleName) throws Exception {
        log.info("Deleting Role: {}", roleName);
        RbacAuthorizationV1Api api = new RbacAuthorizationV1Api(apiClient);
        api.deleteNamespacedRole(roleName, namespace).execute();
        log.info("Role deleted: {}", roleName);
    }

    /**
     * Delete RoleBinding
     */
    public void deleteRoleBinding(String namespace, String roleBindingName) throws Exception {
        log.info("Deleting RoleBinding: {}", roleBindingName);
        RbacAuthorizationV1Api api = new RbacAuthorizationV1Api(apiClient);
        api.deleteNamespacedRoleBinding(roleBindingName, namespace).execute();
        log.info("RoleBinding deleted: {}", roleBindingName);
    }

    public String generateRoleBindingName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
