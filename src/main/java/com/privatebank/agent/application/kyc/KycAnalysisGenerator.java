package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycModelInvocationException;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycAnalysisGenerator {

    private static final String SYSTEM_PROMPT = """
            你是私行 KYC 分析助手。只能使用提供的已脱敏人企家社数据；不得猜测、补充个人身份、名称、联系方式、地址或原文描述。
            P-*、E-*、F-*、O-*、C-* 均为运行时别名，不是可反向识别的名称；不得尝试还原或猜测其真实主体。
            *Category、*Categories、*Signals、*Codes 是由本地规则从原文提取出的受控语义标签，不是原文；只能基于这些标签和其他结构化字段分析。
            先在内部形成结论，再逐条检查：每一项事实、风险等级、风险成因和建议均须由输入中的结构化字段、语义标签或 SRC-* 证据支持。
            不得把未核验、待确认、单位缺失或仅为估算的数据写成确定事实；应明确其验证状态和数据缺口。
            对输入中有记录的每一个 PERSON、ENTERPRISE、FAMILY、SOCIAL 维度至少给出一项具体 finding；每项 finding 应说明事实、验证状态、风险或影响，并列出覆盖该 finding 的 SRC-* 证据。
            分析要具体、完整而非笼统：summary 概括主要风险、正向信息和不确定性；riskAlerts 说明触发原因；recommendedActions 说明核验、复核或提交人工审批的动作。
            输出前必须在内部完成最终复核：逐项审计每个主要事实、风险等级、风险成因、建议及其证据引用；无证据支持的结论必须删除或改写为 dataGaps。
            不得用“规模大、财富高、声誉高、利益冲突”等概括性结论代替证据；只有输入明确支持时才能保留，并写清验证状态。
            每条 finding 的 evidenceRefs 必须覆盖该 finding 中陈述的所有主要事实；只能引用输入中已有的 SRC-*，不得编造编号。
            对 PENDING_CONFIRMATION、UNVERIFIED、估算值或单位不完整的数据，必须明确其不确定性，不得描述为确定事实。
            不得自行作出授信拒绝、服务限制、交易限制等业务决定；只能提出依照既有制度进行人工评估或增强核验的建议。
            必须仅输出一个 JSON 对象，不能使用 Markdown、代码围栏或额外文字。输出必须严格具有以下字段，不能增加或遗漏字段：
            {
              "riskLevel":"LOW|MEDIUM|HIGH|UNKNOWN",
              "summary":"非空字符串",
              "findings":[{"dimension":"PERSON|ENTERPRISE|FAMILY|SOCIAL","riskLevel":"LOW|MEDIUM|HIGH|UNKNOWN","finding":"非空字符串","evidenceRefs":["仅可使用输入中已有的 SRC-* 引用"]}],
              "riskAlerts":["非空字符串"],
              "recommendedActions":["非空字符串"],
              "dataGaps":["非空字符串"]
            }
            所有数组均可为空。结论必须能由输入支持；缺少证据时写入 dataGaps，不得编造证据引用。
            """;

    private final KycModelClient modelClient;
    private final KycOutputValidator outputValidator;
    private final ObjectMapper objectMapper;

    @Value("${private-bank.kyc.max-output-attempts:3}")
    private int maxOutputAttempts;

    public KycGenerationResult generate(KycMaskedInput input) {
        String payload = serializePayload(input);
        KycOutputValidationException lastValidationError = null;
        int attempts = Math.max(1, maxOutputAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String instruction = attempt == 1
                    ? "以下是已脱敏 KYC 输入。请先完成详细分析并在内部逐条自检后输出结论：\n" + payload
                    : "上一版输出未通过格式或脱敏校验。请重新生成严格符合 JSON 合约的结果；"
                            + "不要解释校验原因。请同时逐条检查结论是否有输入证据支持。已脱敏 KYC 输入：\n" + payload;
            try {
                String generated = modelClient.generate(SYSTEM_PROMPT, instruction);
                return new KycGenerationResult(
                        outputValidator.validate(generated, input), attempt, modelClient.modelName());
            } catch (KycOutputValidationException exception) {
                lastValidationError = exception;
                log.warn("KYC generation failed contract validation on attempt {}: {}",
                        attempt, exception.getMessage());
            } catch (KycModelInvocationException exception) {
                log.warn("KYC model invocation returned no usable result on attempt {}/{}: failureType={}",
                        attempt, attempts, modelFailureType(exception));
                if (!isEmptyResponse(exception) || attempt == attempts) {
                    throw exception;
                }
            } catch (RuntimeException exception) {
                throw new KycModelInvocationException("KYC 模型调用失败", exception);
            }
        }
        throw new KycGenerationException("KYC 模型连续返回不符合合约的结果", lastValidationError);
    }

    private String modelFailureType(KycModelInvocationException exception) {
        return isEmptyResponse(exception)
                ? "EMPTY_RESPONSE"
                : "INVOCATION_FAILED";
    }

    private boolean isEmptyResponse(KycModelInvocationException exception) {
        return "KYC 模型未返回可用内容".equals(exception.getMessage());
    }

    private String serializePayload(KycMaskedInput input) {
        try {
            return objectMapper.writeValueAsString(input.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KYC 脱敏输入无法序列化", exception);
        }
    }
}
