package com.ws.controller;

import com.ws.dto.AssignRole;
import com.ws.dto.CreateUser;
import com.ws.service.AzureADService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/azure-mgmt")
public class AzureADController {

    private final AzureADService azureADService;

    @Autowired
    public AzureADController(AzureADService azureADService) {
        this.azureADService = azureADService;
    }


    @GetMapping("/myProfile")
    public ResponseEntity getMyProfileHandler() {
        return new ResponseEntity(azureADService.getMyProfile(), HttpStatus.OK);
    }


    @GetMapping("/fetchAllUsers")
    public ResponseEntity fetchAllUsersHandler() {
        return new ResponseEntity(azureADService.fetchAllUsers(), HttpStatus.OK);
    }


    @PostMapping("/createUser")
    public ResponseEntity createUserHandler(@RequestBody CreateUser createUser) {
        return new ResponseEntity(azureADService.createUser(createUser.getDisplayName(), createUser.getUserPrincipalName(), createUser.getPassword()), HttpStatus.CREATED);
    }


    @PatchMapping("/assignRole")
    public ResponseEntity assignRoleHandler(@RequestBody AssignRole assignRole) {
        azureADService.assignRole(assignRole.getUserId(), assignRole.getRolTemplateId());
        return new ResponseEntity(HttpStatus.NO_CONTENT);
    }

    @PostMapping("/createGroup")
    public ResponseEntity createGroupHandler(@RequestBody Map<String, String> payload) {
        if (!payload.containsKey("displayName") || !payload.containsKey("nickName")) {
            return new ResponseEntity<>("Both 'displayName' and 'nickName' are required", HttpStatus.BAD_REQUEST);
        }

        String displayName = payload.get("displayName");
        String nickName = payload.get("nickName");

        if (displayName == null || nickName == null) {
            return new ResponseEntity<>("Both values must not be null", HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(azureADService.createGroup(displayName, nickName), HttpStatus.CREATED);
    }


    @PostMapping("/addUserToGroup")
    public ResponseEntity addUserToGroup(@RequestBody Map<String, String> payload) {
        if (!payload.containsKey("groupId") || !payload.containsKey("userId")) {
            return new ResponseEntity<>("Missing required fields", HttpStatus.BAD_REQUEST);
        }
        String groupId = payload.get("groupId");
        String userId = payload.get("userId");
        if (groupId == null || userId == null) {
            return new ResponseEntity<>("Please provide some values", HttpStatus.BAD_REQUEST);
        }
        azureADService.addUserToGroup(userId, groupId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }


    @GetMapping("/getRoleTemplates")
    public ResponseEntity getRoleTemplatesHandler() {
        return new ResponseEntity(azureADService.getRoleTemplates(), HttpStatus.OK);
    }


    @PostMapping("/createRole")
    public ResponseEntity createRoleHandler(@RequestBody Map<String, String> payload) {
        if (!payload.containsKey("roleTemplateId") || !payload.containsKey("roleName") || !payload.containsKey("description")) {
            return new ResponseEntity<>("Missing required fields", HttpStatus.BAD_REQUEST);
        }
        String roleTemplateId = payload.get("roleTemplateId");
        String roleName = payload.get("roleName");
        String description = payload.get("description");

        if (roleTemplateId == null || roleName == null) {
            return new ResponseEntity<>("Please provide some values", HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(azureADService.createRole(roleTemplateId, roleName, description), HttpStatus.NO_CONTENT);
    }

}

