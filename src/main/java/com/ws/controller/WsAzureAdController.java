package com.ws.controller;

import com.ws.service.WsAzureAdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ws-azure")
public class WsAzureAdController {

    private final WsAzureAdService wsAzureAdService;

    @Autowired
    public WsAzureAdController(WsAzureAdService wsAzureAdService) {
        this.wsAzureAdService = wsAzureAdService;
    }

    @GetMapping("/users")
    public ResponseEntity getUsersHandler(){
       return wsAzureAdService.getUsers();
    }

    @GetMapping("/groups")
    public ResponseEntity getGroupsHandler(){
       return wsAzureAdService.getGroups();
    }

    @PostMapping("/createGroup")
    public ResponseEntity createGroupHandler(@RequestBody Map<String, Object> groupDetails){
       return wsAzureAdService.createGroup(groupDetails);
    }
}
