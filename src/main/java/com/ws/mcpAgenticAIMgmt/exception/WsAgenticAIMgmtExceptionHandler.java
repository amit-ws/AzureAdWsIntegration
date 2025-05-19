package com.ws.mcpAgenticAIMgmt.exception;

import com.ws.azureAdIntegration.exception.AzureDataException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;


@ControllerAdvice
public class WsAgenticAIMgmtExceptionHandler {

    @ExceptionHandler(WsAgenticAIMgmtException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity handleRuntimeException(AzureDataException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap("message", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity handleRuntimeException(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Collections.singletonMap("message", ex.getMessage()));
    }
}
