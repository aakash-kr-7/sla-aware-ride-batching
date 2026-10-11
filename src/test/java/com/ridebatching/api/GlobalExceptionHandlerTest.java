package com.ridebatching.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    @Test void optimisticConflictResponseMustNotExposePersistenceDetails() {
        var response = new GlobalExceptionHandler().handleConflict(new OptimisticLockingFailureException("internal SQL and entity details"), new MockHttpServletRequest());
        assertThat(response.code()).isEqualTo("STATE_CONFLICT");
        assertThat(response.message()).isEqualTo("Request conflicts with the current state");
    }
}
