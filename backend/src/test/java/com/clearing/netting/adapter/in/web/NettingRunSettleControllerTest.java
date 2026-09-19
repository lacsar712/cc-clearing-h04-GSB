package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.adapter.in.web.auth.AuthUser;
import com.clearing.netting.adapter.in.web.dto.ErrorResponse;
import com.clearing.netting.application.NettingApplicationService;
import com.clearing.netting.domain.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NettingRunSettleControllerTest {

    private final NettingApplicationService nettingService = mock(NettingApplicationService.class);
    private final NettingRunController controller = new NettingRunController(nettingService);
    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void viewerCannotSettle() {
        AuthContext.set(new AuthUser("viewer-user", "VIEWER"));

        DomainException ex = assertThrows(DomainException.class, () -> controller.settle("run-1"));
        assertEquals("FORBIDDEN", ex.getCode());
        verify(nettingService, never()).settle(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unauthenticatedSettleIsRejected() {
        DomainException ex = assertThrows(DomainException.class, () -> controller.settle("run-1"));
        assertEquals("UNAUTHORIZED", ex.getCode());
    }

    @Test
    void invalidStateFromServiceMapsTo4xx() {
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleDomain(
                new DomainException("INVALID_STATE", "settle only allowed for COMPLETED runs"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_STATE", response.getBody().code());
    }

    @Test
    void forbiddenMapsTo403() {
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleDomain(
                new DomainException("FORBIDDEN", "operator role required"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void operatorSettleEndpointStillWired() {
        AuthContext.set(new AuthUser("ops-user", "OPERATOR"));
        when(nettingService.settle("run-1"))
                .thenReturn(com.clearing.netting.domain.model.NettingRun.create(
                        java.time.LocalDate.of(2026, 9, 10), "USD"));

        // Endpoint path/contract unchanged: POST /api/netting-runs/{id}/settle
        controller.settle("run-1");
        verify(nettingService).settle("run-1");
    }
}
