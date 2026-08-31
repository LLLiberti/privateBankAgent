package com.privatebank.business.service.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kycchat.KycChatContext;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Owns workflow authorization and artifact selection for KYC chat. */
@Service
@RequiredArgsConstructor
public class KycChatWorkflowContextService {

    private static final Pattern ALIAS_TOKEN = Pattern.compile("[A-Z]+-\\d+");

    private final WorkflowStateMapper workflowStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public KycChatContext requireContext(
            CurrentUserPrincipal principal,
            String workflowId,
            Long personId,
            String kycArtifactId) {
        WorkflowState workflow = workflowStateMapper.selectById(workflowId);
        if (workflow == null) {
            throw business(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "工作流不存在");
        }
        currentUserService.requireCustomerAccess(principal, workflow.getPersonId());
        if (!workflow.getPersonId().equals(personId)) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "personId与当前工作流绑定的客户不一致");
        }

        AgentArtifact artifact = artifactMapper.selectById(kycArtifactId);
        if (artifact == null) {
            throw business(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "KYC结果不存在");
        }
        if (!workflowId.equals(artifact.getWorkflowId())
                || artifact.getAgentType() != AgentType.CUSTOMER_INSIGHT) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT,
                    "KYC结果与当前工作流不匹配");
        }
        AgentArtifact latest = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        if (latest == null || !kycArtifactId.equals(latest.getArtifactId())) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT,
                    "KYC结果不是当前工作流的最新版本");
        }
        return parseContext(workflowId, personId, artifact);
    }

    private KycChatContext parseContext(String workflowId, Long personId, AgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.getResult())) {
            throw invalidArtifact();
        }
        try {
            JsonNode root = objectMapper.readTree(artifact.getResult());
            JsonNode analysis = root.path("analysis");
            if (!"kyc-result.v2".equals(root.path("contractVersion").asText()) || !analysis.isObject()) {
                throw invalidArtifact();
            }
            String sha256 = root.path("maskedInputSha256").asText(null);
            if (!StringUtils.hasText(sha256)) {
                throw invalidArtifact();
            }
            return new KycChatContext(
                    workflowId,
                    personId,
                    artifact.getArtifactId(),
                    objectMapper.writeValueAsString(analysis),
                    sha256,
                    readAliasMappings(root.path("aliasMappings")));
        } catch (JsonProcessingException exception) {
            throw invalidArtifact();
        }
    }

    private Map<String, String> readAliasMappings(JsonNode node) {
        if (!node.isObject()) {
            throw invalidArtifact();
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (ALIAS_TOKEN.matcher(entry.getKey()).matches()
                    && entry.getValue().isTextual()
                    && StringUtils.hasText(entry.getValue().asText())) {
                mappings.put(entry.getKey(), entry.getValue().asText().trim());
            }
        });
        return mappings;
    }

    private BusinessException invalidArtifact() {
        return business(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "KYC结果格式无效");
    }

    private BusinessException business(HttpStatus status, ErrorCode code, String message) {
        return new BusinessException(status, code, message);
    }
}
