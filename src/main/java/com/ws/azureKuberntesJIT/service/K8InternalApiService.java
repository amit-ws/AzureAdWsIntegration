package com.ws.azureKuberntesJIT.service;


import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
public class K8InternalApiService {

    /**
     * WATCH is follows Event Driven architecture not RestFul so it fetches the real time data, changes pushed by K8 to the method as events
     */

    /*
    * Tracking Pod Lifecycle Events (Example: Watch for Pod Events)
        Here’s a method to watch pod lifecycle events (e.g., when a pod is created, deleted, or updated):
    * */
    public void watchPodEvents() throws Exception {
        ApiClient client = Config.defaultClient();
        CoreV1Api api = new CoreV1Api(client);

        // Using buildCall with null for ApiCallback
        Call call = api.listPodTemplateForAllNamespaces().buildCall(null);
        Watch<V1Pod> watch = Watch.createWatch(
                client,
                call,
                new TypeToken<Watch.Response<V1Pod>>() {
                }.getType()
        );

        for (Watch.Response<V1Pod> item : watch) {
            log.info("Event Type: " + item.type + ", Pod: " + item.object.getMetadata().getName() + ", Pod status; " + item.status);
            // Handle pod lifecycle events here (creation, deletion, status changes, etc.)
        }
    }


    /*
    * Monitoring Pod Resource Usage (CPU/Memory)
        Here’s a method to retrieve the CPU and memory usage of a specific pod using the metrics server in Kubernetes:
    * */
//    public void monitorPodMetrics(String namespace, String podName) throws ApiException {
//        ApiClient client = Config.defaultClient();
//        MetricsV1beta1Api api = new MetricsV1beta1Api(client);
//
//        V1beta1PodMetricsList metricsList = api.getNamespacedPodMetrics(namespace, podName, null);
//        for (V1beta1PodMetrics metrics : metricsList.getItems()) {
//            System.out.println("Pod: " + metrics.getMetadata().getName());
//            metrics.getContainers().forEach(container -> {
//                System.out.println("Container: " + container.getName());
//                System.out.println("CPU Usage: " + container.getUsage().get("cpu"));
//                System.out.println("Memory Usage: " + container.getUsage().get("memory"));
//            });
//        }
//    }


    /*
    * Accessing Service Discovery (DNS)
       Here's how we can query and resolve service names to internal Kubernetes DNS addresses:
    * */
    public void discoverService(String namespace) throws ApiException, IOException {
        ApiClient client = Config.defaultClient();
        CoreV1Api api = new CoreV1Api(client);

        V1ServiceList services = api.listNamespacedService(namespace).execute();
        for (V1Service service : services.getItems()) {
            System.out.println("Service Name: " + service.getMetadata().getName());
            System.out.println("Cluster DNS Name: " + service.getMetadata().getName() + "." + namespace + ".svc.cluster.local");
        }
    }


    /*
    * Monitoring Node Health
        Here’s how we can retrieve the status of nodes in your Kubernetes cluster:
    * */
    public void monitorNodeHealth() throws ApiException, IOException {
        ApiClient client = Config.defaultClient();
        CoreV1Api api = new CoreV1Api(client);

        V1NodeList nodes = api.listNode().execute();
        for (V1Node node : nodes.getItems()) {
            System.out.println("Node: " + node.getMetadata().getName());
            node.getStatus().getConditions().forEach(condition -> {
                System.out.println("Condition: " + condition.getType() + " - Status: " + condition.getStatus());
            });
        }
    }


    /*
    * Custom Resource Definition (CRD) Watch
        Here’s how we can interact with Custom Resources (CRDs) if you’ve defined any custom resources in your cluster:
    * */
    public void watchCustomResources(String group, String version, String plural) throws ApiException, IOException {
        ApiClient client = Config.defaultClient();
        CustomObjectsApi api = new CustomObjectsApi(client);

        Call call = api.listClusterCustomObject(group, version, plural).buildCall(null);

        // Use listClusterCustomObject to watch custom resources
        Watch<V1ObjectMeta> watch = Watch.createWatch(
                client,
                call,
                new TypeToken<Watch.Response<V1ObjectMeta>>() {
                }.getType()
        );

        for (Watch.Response<V1ObjectMeta> item : watch) {
            System.out.println("Custom Resource Event: " + item.type + " - " + item.object.getName());
        }
    }


    /*
    * Watch Pod Status and Events
        we can use Kubernetes Watch API to listen for pod status and trigger events based on pod lifecycle changes.
    * */
    public void watchPodStatus() throws ApiException, IOException {
        ApiClient client = Config.defaultClient();
        CoreV1Api api = new CoreV1Api(client);

        Call call = api.listPodForAllNamespaces().buildCall(null);

        Watch<V1Pod> podWatch = Watch.createWatch(
                client,
                call,
                new TypeToken<Watch.Response<V1Pod>>() {
                }.getType()
        );

        for (Watch.Response<V1Pod> podEvent : podWatch) {
            System.out.println("Event Type: " + podEvent.type + ", Pod Name: " + podEvent.object.getMetadata().getName());
            // Handle pod creation, deletion, and status updates here.
        }
    }

}