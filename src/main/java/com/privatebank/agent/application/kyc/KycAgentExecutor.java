package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycAgentExecutor implements BusinessAgentExecutor<KycMaskedInput, KycStructuredResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行 KYC 专业分析 Agent。只能使用输入中已经脱敏的人、企、家、社数据，不得猜测、补充或还原任何真实身份信息。
            每项事实、风险等级、风险成因和建议都必须由结构化字段、受控语义标签或输入中已有的 SRC-* 证据支持。
            未核验、待确认、估算值或单位缺失的数据不得写成确定事实，应明确其不确定性并写入 dataGaps。
            对输入中有记录的 PERSON、ENTERPRISE、FAMILY、SOCIAL 维度给出具体 finding；每条 finding 的 evidenceRefs 必须覆盖其中陈述的主要事实。
            managerSupplement.signals 是运行时受控语义线索，不是 SRC-* 证据。不得为它编造 evidenceRefs；仅由该线索支持的内容必须写成待核验的 dataGaps 或人工复核建议，不得写成确定事实 finding。
            relationshipGraph 是来自 Neo4j 的独立关系投影。必须将它与人、企、家、社结构化事实比较，判断其贡献是 INCREMENTAL、CONFIRMATORY、NO_INCREMENT 或 NOT_AVAILABLE，并填写 graphAssessment。
            只有 Neo4j 提供了结构化事实中没有的关系、二跳关联或跨记录风险链路时，才能标记 INCREMENTAL；此时至少一条 finding 必须引用 relationshipGraph.evidenceRefs 中的证据。
            CONFIRMATORY 表示图关系只交叉印证已有事实；NO_INCREMENT 表示图谱可用但没有形成新增或有效印证，此时 graphAssessment.evidenceRefs 可以为空；INCREMENTAL 和 CONFIRMATORY 必须引用 relationshipGraph.evidenceRefs 中的证据。不得为了使用 Neo4j 而虚构风险。
            只能引用输入中已有的 SRC-* 编号，不得编造证据编号，不得输出原始姓名、企业名、联系方式、地址或其他被禁止信息。
            不得根据出生年份、任职经历、财富数值、事件时间或关系图组合猜测客户及关联实体的真实身份；所有主体只能使用输入中的 P-*、E-*、O-*、F-*、C-* 等别名。
            dataCompleteness 只描述安全省略和截断；存在 omissions 或 truncatedSections 时，应在 dataGaps 中说明分析范围受限，不得补全被省略内容。
            不得自行作出授信拒绝、服务限制或交易限制等业务决定，只能提出人工核验或复核建议。
            输出前逐项复核结论与证据；无证据支持的结论必须删除或改写为 dataGaps。
            findings、riskAlerts、recommendedActions、dataGaps 各自最多 20 项；每条 finding 的 evidenceRefs 最多 10 项，且每项必须是允许引用列表中的单个 SRC-* 编号。
            """;

    private final StructuredAgentRuntime runtime;
    private final KycOutputValidator outputValidator;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;

    @Override
    public AgentType agentType() {
        return AgentType.CUSTOMER_INSIGHT;
    }

    @Override
    public AgentExecutionResult<KycStructuredResult> execute(AgentExecutionRequest<KycMaskedInput> request) {
        KycOutputValidationException lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            StructuredAgentDefinition<KycStructuredResult> definition = new StructuredAgentDefinition<>(
                    "kyc-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    KycStructuredResult.class,
                    Math.max(1, properties.maxIterations()),
                    null);
            AgentExecutionResult<KycStructuredResult> runtimeResult = runtime.execute(request, definition);
            try {
                String validated = outputValidator.validate(serialize(runtimeResult.output()), request.input());
                return new AgentExecutionResult<>(
                        deserialize(validated), attempt, runtimeResult.modelName());
            } catch (KycOutputValidationException exception) {
                lastValidationError = exception;
                log.warn("KYC structured result failed business validation on attempt {}: {}",
                        attempt, exception.getMessage());
            }
        }
        throw new KycGenerationException("KYC Agent 连续返回不符合业务约束的结果", lastValidationError);
    }

    public KycGenerationResult toGenerationResult(AgentExecutionResult<KycStructuredResult> result) {
        return new KycGenerationResult(serialize(result.output()), result.attempts(), result.modelName());
    }

    private String userPrompt(KycMaskedInput input, KycOutputValidationException validationFailure) {
        String instruction = validationFailure == null
                ? "请基于以下已脱敏 KYC 输入完成分析并在内部完成证据复核。"
                : "上一版结构化结果未通过证据、脱敏或业务约束校验。具体失败原因："
                        + validationFailure.getMessage()
                        + "。允许引用的全部证据编号："
                        + input.evidenceReferences().keySet().stream().sorted().toList()
                        + "。请针对该原因从头重写结果，不得编造证据编号。"
                        + "若失败原因涉及直接标识信息，必须删除所有真实人名、机构名、地点名和联系方式，"
                        + "且不得根据业务事实猜测专有名词；主体一律只写 P-*、E-*、O-*、F-*、C-* 别名。";
        return instruction + "\n" + serialize(input.payload());
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KYC 结构化数据无法序列化", exception);
        }
    }

    private KycStructuredResult deserialize(String json) {
        try {
            return objectMapper.readValue(json, KycStructuredResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已校验的 KYC 结果无法反序列化", exception);
        }
    }
}
