//package com.ws.controller;
//
//import com.ws.service.AzureAdTestService;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/test")
//@Slf4j
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureAdTestController {
//
//    final AzureAdTestService azureAdTestService;
//
//    @Autowired
//    public AzureAdTestController(AzureAdTestService azureAdTestService) {
//        this.azureAdTestService = azureAdTestService;
//    }
//
//    @GetMapping("/users")
//    public void fetchUserHandler(){
//        azureAdTestService.fetchUser();
//    }
//
//}
