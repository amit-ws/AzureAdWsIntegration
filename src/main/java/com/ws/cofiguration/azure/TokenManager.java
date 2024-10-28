package com.ws.cofiguration.azure;

import lombok.Getter;
import lombok.Setter;


@Setter
@Getter
public class TokenManager {
    private static TokenManager instance;
    private String accessToken;

    public static synchronized TokenManager getInstance() {
        if (instance == null) {
            instance = new TokenManager();
        }
        return instance;
    }
}
