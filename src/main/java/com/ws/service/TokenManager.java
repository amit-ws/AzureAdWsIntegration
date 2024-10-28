package com.ws.service;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TokenManager {
    String accessToken;
    String refreshToken;
    String scope;
    String tokenType;
    Integer expireIn;
    Integer extendedExpireIn;

    static TokenManager instance;

    private TokenManager() {}

    public static TokenManager getInstance() {
        if (instance == null) {
            instance = new TokenManager();
        }
        return instance;
    }

    public void setFieldValues(Map tokenResponse){
        this.setAccessToken((String) tokenResponse.get("access_token"));
        this.setRefreshToken((String) tokenResponse.get("refresh_token"));
        this.setTokenType((String) tokenResponse.get("token_type"));
        this.setScope((String) tokenResponse.get("scope"));
        this.setExpireIn((Integer) tokenResponse.get("expires_in"));
        this.setExtendedExpireIn((Integer) tokenResponse.get("ext_expires_in"));
    }
}
