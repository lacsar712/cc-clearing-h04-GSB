package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.dto.ErrorResponse;
import com.clearing.netting.domain.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void invalidStateMapsTo4xx() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDomain(new DomainException("INVALID_STATE", "only COMPLETED runs can be settled"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_STATE", response.getBody().code());
    }

    @Test
    void forbiddenMapsTo403() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDomain(new DomainException("FORBIDDEN", "operator role required"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
