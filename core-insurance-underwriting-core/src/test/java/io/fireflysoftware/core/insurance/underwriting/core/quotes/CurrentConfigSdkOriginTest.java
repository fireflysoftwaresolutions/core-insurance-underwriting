package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import io.fireflysoftware.common.config.sdk.api.TenantResourceBindingCurrentApi;
import io.fireflysoftware.common.config.sdk.invoker.ApiClient;
import io.fireflysoftware.common.config.sdk.model.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class CurrentConfigSdkOriginTest {
    @Test void actualCurrentSdkAbiAndClassesMustComeFromTheSelectedOriginalSourceReactor()throws Exception {
        var expected=Path.of("../../core-common-config-mgmt/core-common-config-mgmt-sdk/target/classes").toRealPath();
        for(var type:List.of(TenantResourceBindingCurrentApi.class,ApiClient.class,TenantResourceBindingCurrent.class,
                TenantResourceBindingDefinition.class,TenantResourceBindingReference.class,TenantResourceBindingEligibility.class,
                TenantResourceBindingNamespace.class,TenantResourceBindingResourceTenant.class))
            FreshSourceSdkFixture.require(type,expected);
        assertNotNull(ApiClient.class.getConstructor(WebClient.class));
        assertNotNull(TenantResourceBindingCurrentApi.class.getConstructor(ApiClient.class));
        var operation=TenantResourceBindingCurrentApi.class.getMethod("observeCurrentTenantResourceBinding");
        assertEquals(0,operation.getParameterCount());assertEquals(Mono.class,operation.getReturnType());
        assertTrue(operation.getGenericReturnType().getTypeName().contains(TenantResourceBindingCurrent.class.getName()));
        for(String getter:List.of("getObservedAtEpochMillis","getValidUntilEpochMillis","getControlVersion"))
            assertEquals(Long.class,TenantResourceBindingCurrent.class.getMethod(getter).getReturnType());
        assertEquals(Long.class,TenantResourceBindingEligibility.class.getMethod("getTenantGeneration").getReturnType());
        assertEquals(Long.class,TenantResourceBindingEligibility.class.getMethod("getStatusGeneration").getReturnType());
    }
}
