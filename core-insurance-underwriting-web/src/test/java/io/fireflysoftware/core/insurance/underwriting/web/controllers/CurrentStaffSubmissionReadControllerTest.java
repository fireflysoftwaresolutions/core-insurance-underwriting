package io.fireflysoftware.core.insurance.underwriting.web.controllers;

import io.fireflysoftware.core.insurance.underwriting.core.mappers.SubmissionMapper;
import io.fireflysoftware.core.insurance.underwriting.core.services.SubmissionService;
import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffSubmissionReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentStaffSubmissionReadControllerTest {
    @Test
    void currentReadRefusalIsForbiddenWithoutLegacyFallbackOrMapping() {
        var id = UUID.randomUUID();
        var reader = mock(StaffSubmissionReader.class);
        var legacy = mock(SubmissionService.class);
        var mapper = mock(SubmissionMapper.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("currentReader", reader);
        when(reader.read(id)).thenReturn(Mono.error(new SecurityException("controlled refusal")));
        var controller = new SubmissionController(legacy,
                beans.getBeanProvider(StaffSubmissionReader.class), mapper);
        var refusal = assertThrows(ResponseStatusException.class, () -> controller.getSubmissionById(id).block());
        assertEquals(403, refusal.getStatusCode().value());
        verify(reader).read(id);
        verifyNoInteractions(legacy, mapper);
    }
}
