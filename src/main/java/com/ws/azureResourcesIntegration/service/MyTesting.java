//package com.ws.azureResourcesIntegration.service;
//
//import java.util.Arrays;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
//public class MyTesting {
//
//    public static void main(String[] args) {
//        // Sample method call
//        testDeletedSubscriptions();
//    }
//
//    public static void testDeletedSubscriptions() {
//        Set<String> savedSubIds = new HashSet<>(Arrays.asList("1", "2"));
//        Set<String> requestedSubIds = new HashSet<>(Arrays.asList(""));
//
//        Set<String> toBeDeletedIds = savedSubIds;
//        toBeDeletedIds.removeAll(requestedSubIds);
//
//        System.out.println("Subscriptions to be deleted: " + toBeDeletedIds);
//
//    }
//}
