package com.privatebank.agent.application.kycchat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class KycChatContextService {

    public static final String SAME_AS_KYC_INPUT = "SAME_AS_KYC_INPUT";
    public static final String CURRENT_DATA_CHANGED_SINCE_KYC = "CURRENT_DATA_CHANGED_SINCE_KYC";

    private static final Pattern ALIAS_TOKEN = Pattern.compile("[A-Z]+-\\d+");

    private final WorkflowStateMapper workflowStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final CurrentUserService currentUserService;
    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final KycChatAliasNormalizer aliasNormalizer;
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

    @Transactional(readOnly = true)
    public KycChatPreparedTurn prepareTurn(
            KycChatContext context,
            String managerMessage,
            Map<String, String> canonicalMappings) {
        KycCustomerData customerData = customerDataLoader.load(context.personId());
        KycMaskedInput currentSnapshot = dataMaskingService.mask(customerData);
        KycMaskedInput messageInput = dataMaskingService.mask(
                customerData, new KycRuntimeSupplement(managerMessage, List.of()));
        Object maskedMessage = messageInput.payload().get("managerInstruction");
        if (!(maskedMessage instanceof String text) || !StringUtils.hasText(text)) {
            throw business(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                    "客户经理消息脱敏后为空");
        }

        KycChatAliasNormalizer.AliasPlan plan = aliasNormalizer.plan(
                messageInput.aliasMappings(), canonicalMappings);
        String comparison = context.maskedInputSha256().equals(currentSnapshot.sha256())
                ? SAME_AS_KYC_INPUT
                : CURRENT_DATA_CHANGED_SINCE_KYC;
        return new KycChatPreparedTurn(
                aliasNormalizer.normalizeText(text, plan),
                aliasNormalizer.normalizePayload(currentSnapshot.payload(), plan),
                comparison,
                plan.canonicalMappings());
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
