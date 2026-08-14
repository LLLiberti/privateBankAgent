package com.privatebank.business.service.customer;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.entity.auth.RoleName;
import com.privatebank.business.graph.GraphGatewayException;
import com.privatebank.business.graph.GraphMapper;
import com.privatebank.business.graph.GraphQueryPolicy;
import com.privatebank.business.graph.GraphSlice;
import com.privatebank.business.graph.Neo4jGraphGateway;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerGraphServiceTest {
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final GraphQueryPolicy policy = mock(GraphQueryPolicy.class);
    private final Neo4jGraphGateway gateway = mock(Neo4jGraphGateway.class);
    private final GraphMapper mapper = mock(GraphMapper.class);
    private final CustomerGraphService service = new CustomerGraphService(
            currentUserService, policy, gateway, mapper);
    private final CurrentUserPrincipal principal = new CurrentUserPrincipal(
            "RM-001", "客户经理", RoleName.CUSTOMER_MANAGER);

    @Test
    void rejectsMalformedNodeIdBeforeCallingNeo4j() {
        when(policy.enabled()).thenReturn(true);
        assertThatThrownBy(() -> service.neighbors(principal, 1L, "MATCH (n)", null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                });
        verify(currentUserService).requireCustomerAccess(principal, 1L);
        verify(gateway, never()).nodeExists(anyString(), any());
    }

    @Test
    void rejectsExistingNodeOutsideCustomerReachability() {
        preparePolicy();
        when(gateway.nodeExists("enterprise:999", Duration.ofSeconds(2))).thenReturn(true);
        when(gateway.isReachable("person:1", "enterprise:999", 3, Duration.ofSeconds(2))).thenReturn(false);
        assertThatThrownBy(() -> service.neighbors(principal, 1L, "enterprise:999", null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.GRAPH_NODE_OUT_OF_SCOPE);
                });
        verify(gateway, never()).getNeighbors(anyString(), anyList(), anyList(), anyInt(), any());
    }

    @Test
    void isolatesNeo4jFailureAsGraphServiceUnavailable() {
        preparePolicy();
        when(policy.initialNodeLimit(null)).thenReturn(100);
        when(gateway.getNeighbors(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenThrow(new GraphGatewayException("connection failed", new RuntimeException()));
        assertThatThrownBy(() -> service.initialGraph(principal, 1L, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.GRAPH_SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void returnsGraphRootNotFoundForEmptyQuery() {
        preparePolicy();
        when(policy.initialNodeLimit(null)).thenReturn(100);
        when(gateway.getNeighbors(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenReturn(new GraphSlice(List.of(), false));
        assertThatThrownBy(() -> service.initialGraph(principal, 1L, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.GRAPH_ROOT_NOT_FOUND);
                });
    }

    private void preparePolicy() {
        when(policy.enabled()).thenReturn(true);
        when(policy.queryTimeout()).thenReturn(Duration.ofSeconds(2));
        when(policy.maxDepth()).thenReturn(3);
        when(policy.neo4jLabels(any())).thenReturn(List.of("Person", "Enterprise"));
        when(policy.allowedRelationTypes()).thenReturn(List.of("CHAIRMAN_OF"));
        when(policy.expandNodeLimit(null)).thenReturn(50);
    }
}
