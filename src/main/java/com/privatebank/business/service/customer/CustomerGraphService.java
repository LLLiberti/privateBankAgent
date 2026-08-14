package com.privatebank.business.service.customer;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.dto.customer.graph.GraphNodeType;
import com.privatebank.business.dto.customer.graph.GraphResponse;
import com.privatebank.business.graph.GraphGatewayException;
import com.privatebank.business.graph.GraphMapper;
import com.privatebank.business.graph.GraphQueryPolicy;
import com.privatebank.business.graph.GraphSlice;
import com.privatebank.business.graph.Neo4jGraphGateway;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerGraphService {
    private final CurrentUserService currentUserService;
    private final GraphQueryPolicy policy;
    private final Neo4jGraphGateway gateway;
    private final GraphMapper mapper;

    public GraphResponse initialGraph(
            CurrentUserPrincipal principal, Long customerId,
            Set<GraphNodeType> types, Integer maxNodes) {
        currentUserService.requireCustomerAccess(principal, customerId);
        ensureEnabled();
        String rootId = rootId(customerId);
        int nodeLimit = policy.initialNodeLimit(maxNodes);
        long started = System.nanoTime();
        try {
            GraphSlice slice = gateway.getNeighbors(
                    rootId, policy.neo4jLabels(types), policy.allowedRelationTypes(),
                    Math.max(0, nodeLimit - 1), policy.queryTimeout());
            if (slice.rows().isEmpty()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.GRAPH_ROOT_NOT_FOUND, "客户知识图谱根节点不存在");
            }
            GraphResponse response = mapper.map(customerId, rootId, slice);
            logResult(principal, customerId, "INITIAL", rootId, response, started);
            return response;
        } catch (GraphGatewayException exception) {
            throw unavailable(exception);
        }
    }

    public GraphResponse neighbors(
            CurrentUserPrincipal principal, Long customerId, String nodeId,
            Set<GraphNodeType> types, Integer maxNodes) {
        currentUserService.requireCustomerAccess(principal, customerId);
        ensureEnabled();
        try {
            GraphNodeType.fromNodeId(nodeId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "图谱节点ID格式无效");
        }
        String rootId = rootId(customerId);
        long started = System.nanoTime();
        try {
            if (!gateway.nodeExists(nodeId, policy.queryTimeout())) {
                throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.GRAPH_NODE_NOT_FOUND, "图谱节点不存在");
            }
            if (!nodeId.equals(rootId)
                    && !gateway.isReachable(rootId, nodeId, policy.maxDepth(), policy.queryTimeout())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.GRAPH_NODE_OUT_OF_SCOPE, "图谱节点不属于当前客户");
            }
            GraphSlice slice = gateway.getNeighbors(
                    nodeId, policy.neo4jLabels(types), policy.allowedRelationTypes(),
                    Math.max(0, policy.expandNodeLimit(maxNodes) - 1), policy.queryTimeout());
            GraphResponse response = mapper.map(customerId, rootId, slice);
            logResult(principal, customerId, "EXPAND", nodeId, response, started);
            return response;
        } catch (GraphGatewayException exception) {
            throw unavailable(exception);
        }
    }

    private void ensureEnabled() {
        if (!policy.enabled()) throw unavailable(null);
    }

    private BusinessException unavailable(Throwable cause) {
        if (cause != null) log.warn("Neo4j graph query unavailable: {}", cause.getMessage());
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "客户知识图谱服务暂时不可用");
    }

    private String rootId(Long customerId) { return "person:" + customerId; }

    private void logResult(CurrentUserPrincipal principal, Long customerId, String queryType,
                           String nodeId, GraphResponse response, long started) {
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        log.info("graph traceId={} userId={} customerId={} queryType={} nodeId={} nodesReturned={} edgesReturned={} truncated={} durationMs={}",
                MDC.get("traceId"), principal.userId(), customerId, queryType, nodeId,
                response.nodes().size(), response.edges().size(), response.truncated(), durationMs);
    }
}
