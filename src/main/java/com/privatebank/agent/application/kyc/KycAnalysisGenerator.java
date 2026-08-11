package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycModelInvocationException;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KycAnalysisGenerator {

    private static final String SYSTEM_PROMPT = """
            你是私行 KYC 分析助手。只能使用提供的已脱敏人企家社数据；不得猜测、补充个人身份、名称、联系方式、地址或原文描述。
            P-*、E-*、F-*、O-*、C-* 均为运行时别名，不是可反向识别的名称；不得尝试还原或猜测其真实主体。
            *Category、*Categories、*Signals、*Codes 是由本地规则从原文提取出的受控语义标签，不是原文；只能基于这些标签和其他结构化字段分析。
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
                    ? "以下是已脱敏 KYC 输入，请进行分析：\n" + payload
                    : "上一版输出未通过格式或脱敏校验。请重新生成严格符合 JSON 合约的结果；"
                            + "不要解释校验原因。已脱敏 KYC 输入：\n" + payload;
            try {
                String generated = modelClient.generate(SYSTEM_PROMPT, instruction);
                return new KycGenerationResult(
                        outputValidator.validate(generated, input), attempt, modelClient.modelName());
            } catch (KycOutputValidationException exception) {
                lastValidationError = exception;
            } catch (KycModelInvocationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new KycModelInvocationException("KYC 模型调用失败", exception);
            }
        }
        throw new KycGenerationException("KYC 模型连续返回不符合合约的结果", lastValidationError);
    }

    private String serializePayload(KycMaskedInput input) {
        try {
            return objectMapper.writeValueAsString(input.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KYC 脱敏输入无法序列化", exception);
        }
    }
}
