package com.banka1.order.controller;

import com.banka1.order.dto.OrderOverviewResponse;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.entity.enums.OrderOverviewStatusFilter;
import com.banka1.order.service.OrderCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderCreationService orderCreationService;

    private OrderController controller;

    @BeforeEach
    void setUp() {
        controller = new OrderController(orderCreationService);
    }

    @Test
    void getOrders_methodExistsAndDelegatesToService() {
        OrderOverviewResponse row = new OrderOverviewResponse();
        row.setOrderId(1L);
        when(orderCreationService.getOrders(OrderOverviewStatusFilter.ALL)).thenReturn(List.of(row));

        ResponseEntity<List<OrderOverviewResponse>> response = controller.getOrders(OrderOverviewStatusFilter.ALL);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().getFirst().getOrderId()).isEqualTo(1L);
        verify(orderCreationService).getOrders(OrderOverviewStatusFilter.ALL);
    }

    @Test
    void supervisorPortalEndpointsUseExpectedMappingsAndSecurity() throws Exception {
        assertGetMapping("getOrders");
        assertPreAuthorize("getOrders", "hasRole('SUPERVISOR')");

        assertPutMapping("approveOrder", "/{id}/approve");
        assertPreAuthorize("approveOrder", "hasRole('SUPERVISOR')");

        assertPutMapping("declineOrder", "/{id}/decline");
        assertPreAuthorize("declineOrder", "hasRole('SUPERVISOR')");

        assertPutMapping("cancelOrder", "/{id}/cancel", Long.class);
        assertPreAuthorize("cancelOrder", "hasRole('SUPERVISOR')", Long.class);
    }

    @Test
    void legacyClientCancelEndpointIsStillPresentForExistingFlows() throws Exception {
        Method method = OrderController.class.getDeclaredMethod("cancelOrder", Jwt.class, Long.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/{id}/cancel");
        assertThat(preAuthorize.value()).isEqualTo("hasRole('CLIENT') or hasRole('ACTUARY')");
    }

    private void assertGetMapping(String methodName) throws Exception {
        Method method = OrderController.class.getDeclaredMethod(methodName, OrderOverviewStatusFilter.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
    }

    private void assertPutMapping(String methodName, String path, Class<?>... parameterTypes) throws Exception {
        Method method = OrderController.class.getDeclaredMethod(methodName, parameterTypes.length == 0 ? new Class<?>[]{Jwt.class, Long.class} : parameterTypes);
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPreAuthorize(String methodName, String expected, Class<?>... parameterTypes) throws Exception {
        Class<?>[] resolvedParameterTypes = parameterTypes.length == 0
                ? ("getOrders".equals(methodName) ? new Class<?>[]{OrderOverviewStatusFilter.class} : new Class<?>[]{Jwt.class, Long.class})
                : parameterTypes;
        Method method = OrderController.class.getDeclaredMethod(methodName, resolvedParameterTypes);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expected);
    }
}
