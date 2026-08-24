package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CfsDesignAgentExecutor implements BusinessAgentExecutor<CfsDesignInput, CfsDesignResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行 CFS 方案设计 Agent。
            基于 KYC、市场洞察和产品专家结果，按照 CFS“3+6”结构生成方案初稿。
            所有结论必须来自输入 Artifact，不得编造事实或证据。
            生成客户信息章节时，必须调用 getCustomerProfile 工具获取脱敏后的客户资料；禁止编造客户身份信息。
            人、企、家、社四维需求基于 KYC 结果由模型分析，可适当保留 KYC 内容；服务建议和附件基于所有上游 Agent 内容由模型分析。
            输出必须严格符合以下 CfsDesignResult 字段格式：
            {
              "customerId": string,
              "inputArtifactRefs": {"kyc": string, "market": string, "kyp": string},
              "cfsVersion": integer,
              "marketingStrategy": string,
              "communicationGuide": string,
              "comprehensiveRiskAssessment": string,
              "cfsStructure": {
                "chapter1CustomerInfo": string,
                "chapter2ServicePlan": string,
                "chapter3MarketingStrategy": string,
                "attachments": [string]
              },
              "pendingVerificationItems": [string],
              "estimatedDataItems": [string],
              "sourceRefs": [string],
              "productEvidenceRefs": [
                {"chunkId": string, "documentId": string, "productId": string,
                 "content": string, "sourceId": string, "score": number}
              ],
              "ruleRefs": [string]
            }
            3+6 CFS 内容要求必须完整输出。3 个章节字段必须有内容，attachments 必须严格按照 1 至 6 的顺序输出 6 项，且每一项必须有内容。章节和附件的具体要求如下：
            {
              "threePlusSixRequirements": {
                "chapters": [
                  {
                    "chapterNo": 1,
                    "title": "客户信息",
                    "outputField": "cfsStructure.chapter1CustomerInfo",
                    "requiredContent": ["客户个人情况", "公司及行业情况"]
                  },
                  {
                    "chapterNo": 2,
                    "title": "服务方案",
                    "outputField": "cfsStructure.chapter2ServicePlan",
                    "requiredContent": ["人、企、家、社四维需求", "服务建议"]
                  },
                  {
                    "chapterNo": 3,
                    "title": "营销策略",
                    "outputField": "cfsStructure.chapter3MarketingStrategy",
                    "requiredContent": ["接触路径", "关键窗口期", "待进一步了解问题"]
                  }
                ],
                "attachments": [
                  {"attachmentNo": 1, "title": "实控人及其他关键人物详情", "outputField": "cfsStructure.attachments[0]"},
                  {"attachmentNo": 2, "title": "公司大事记及财务分析", "outputField": "cfsStructure.attachments[1]"},
                  {"attachmentNo": 3, "title": "公司主要产品及服务介绍", "outputField": "cfsStructure.attachments[2]"},
                  {"attachmentNo": 4, "title": "行业知识及竞争对手情况", "outputField": "cfsStructure.attachments[3]"},
                  {"attachmentNo": 5, "title": "公司及个人舆情", "outputField": "cfsStructure.attachments[4]"},
                  {"attachmentNo": 6, "title": "工作优势及营销话术", "outputField": "cfsStructure.attachments[5]"}
                ]
              }
            }
            最终报告严格按照“3 个章节 + 6 个附件”导出。每个章节和附件必须使用清晰的小标题、分点和换行，
            不得把多个主题压缩为一整段；正文统一使用中文，不得直接输出 PERSON、ENTERPRISE、FAMILY、SOCIAL、
            LOW、MEDIUM、HIGH、UNKNOWN、UNVERIFIED、PENDING_CONFIRMATION 等后端枚举值。
            chapter3MarketingStrategy 只负责深化路径、关键窗口期、待进一步了解问题和推进动作；
            marketingStrategy 仅作为内部简要摘要，不得照抄 chapter3MarketingStrategy；
            communicationGuide 负责具体沟通表达，其完整可用内容必须归入附件 6“工作优势及营销话术”。
            附件 6 只输出开场、需求探询、价值表达、异议处理和下一步邀约等可直接使用的话术，不得重复第三章的策略分析。
            sourceRefs、productEvidenceRefs、ruleRefs 合计最多 10 项，只能引用输入中真实存在的来源，不得编造代号。
            所有数组字段都必须存在，可以为空数组；数组中的对象元素不能为 null。
            但 cfsStructure.attachments 是 3+6 的固定结构，不允许为空数组，必须恰好包含 6 个非空字符串。
            customerId、inputArtifactRefs 中的三个 ID、cfsVersion 必须有效。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final CustomerProfileTool customerProfileTool;
    private final CfsDesignResultValidator validator = new CfsDesignResultValidator();

    @Autowired
    public CfsDesignAgentExecutor(
            StructuredAgentRuntime runtime,
            ObjectMapper objectMapper,
            AgentScopeProperties properties,
            CustomerProfileTool customerProfileTool) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.customerProfileTool = customerProfileTool;
    }

    public CfsDesignAgentExecutor(
            StructuredAgentRuntime runtime,
            ObjectMapper objectMapper,
            AgentScopeProperties properties) {
        this(runtime, objectMapper, properties, null);
    }

    @Override
    public AgentType agentType() {
        return AgentType.SOLUTION_DESIGN;
    }

    @Override
    public AgentExecutionResult<CfsDesignResult> execute(AgentExecutionRequest<CfsDesignInput> request) {
        String lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        long deadlineNanos = System.nanoTime() + properties.cfsTotalTimeout().toNanos();
        Toolkit toolkit = customerProfileTool == null ? null : buildToolkit();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalArgumentException("CFS 方案生成总执行超时");
            }
            StructuredAgentDefinition<CfsDesignResult> definition = new StructuredAgentDefinition<>(
                    "cfs-design-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    CfsDesignResult.class,
                    Math.max(1, properties.maxIterations()),
                    toolkit);
            AgentExecutionResult<CfsDesignResult> result = runtime.execute(request, definition);
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalArgumentException("CFS 方案生成总执行超时");
            }
            try {
                validator.validate(result.output());
                return new AgentExecutionResult<>(result.output(), attempt, result.modelName());
            } catch (IllegalArgumentException exception) {
                lastValidationError = exception.getMessage();
                log.warn("CFS design structured result failed validation on attempt {}: {}",
                        attempt, lastValidationError);
            }
        }
        throw new IllegalArgumentException(
                "CFS 方案 Agent 连续返回不符合格式要求的结果：" + lastValidationError);
    }

    private Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(customerProfileTool);
        return toolkit;
    }

    private String userPrompt(CfsDesignInput input, String validationFailure) {
        String instruction = validationFailure == null
                ? "请基于以下三个上游 Artifact 内容生成 CFS 方案，并严格按照上述字段格式输出。"
                : "上一版 CFS 方案未通过格式校验，请修正后重新生成。失败原因：" + validationFailure;
        return instruction + "\n" + write(input);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CFS 方案输入无法序列化", exception);
        }
    }
}
