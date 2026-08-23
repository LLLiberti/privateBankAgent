package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.service.document.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CfsReportExportService {

    public static final String GENERATOR = "CFS_REPORT_EXPORT";

    private final AgentArtifactMapper artifactMapper;
    private final WorkflowStateMapper workflowMapper;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final CfsReportDocumentFactory documentFactory;
    private final CfsMarkdownRenderer markdownRenderer;
    private final CfsDocxRenderer docxRenderer;
    private final CfsPdfRenderer pdfRenderer;

    @Transactional
    public ExportResult export(String workflowId, String cfsArtifactId, String complianceArtifactId) {
        WorkflowState workflow = requireWorkflow(workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.GENERATING_OUTPUT
                && workflow.getWorkflowStatus() != WorkflowStatus.COMPLETED) {
            throw new IllegalStateException("CFS reports can only be exported while output is being generated");
        }
        AgentArtifact cfs = requireArtifact(cfsArtifactId, workflowId, AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = requireArtifact(complianceArtifactId, workflowId, AgentType.COMPLIANCE_CHECK);
        if (!isHumanReviewableCompliance(compliance.getComplianceResult())) {
            throw new IllegalStateException("该合规结果不支持人工审核后导出");
        }

        ObjectNode cfsJson = objectNode(cfs.getResult(), "CFS结果不是有效JSON对象");
        JsonNode complianceJson = json(compliance.getResult(), "合规结果不是有效JSON");
        String referencedCfs = complianceJson.path("cfsArtifactRef").asText("");
        if (!StringUtils.hasText(referencedCfs)) {
            referencedCfs = complianceJson.path("cfsArtifactId").asText("");
        }
        if (!cfsArtifactId.equals(referencedCfs)) {
            throw new IllegalStateException("合规结果引用的CFS与待导出Artifact不一致");
        }

        OffsetDateTime generatedAt = OffsetDateTime.now(ZoneId.systemDefault());
        CfsReportDocument report = documentFactory.create(
                cfsJson, workflowId, cfsArtifactId, complianceArtifactId, generatedAt);
        String baseName = reportBaseName(report);
        List<RenderedReport> rendered = List.of(
                new RenderedReport(Format.MARKDOWN, baseName + ".md", markdownRenderer.render(report)),
                new RenderedReport(Format.WORD, baseName + ".docx", docxRenderer.render(report)),
                new RenderedReport(Format.PDF, baseName + ".pdf", pdfRenderer.render(report)));

        List<FileMetadata> files = new ArrayList<>();
        for (RenderedReport reportFile : rendered) {
            FileStorageService.StoredFile stored = fileStorageService.storeGenerated(
                    workflowId, cfsArtifactId, reportFile.fileName(), reportFile.content());
            files.add(new FileMetadata(
                    fileId(cfsArtifactId, reportFile.format()),
                    reportFile.format().name(),
                    stored.fileName(),
                    reportFile.format().contentType,
                    reportFile.content().length,
                    stored.path(),
                    generatedAt.toString(),
                    complianceArtifactId,
                    GENERATOR));
        }

        cfsJson.set("files", mergeFiles(cfsJson.path("files"), files));
        cfsJson.put("reportExportedAt", generatedAt.toString());
        cfs.setResult(write(cfsJson));
        if (artifactMapper.updateById(cfs) != 1) {
            throw new IllegalStateException("CFS报告文件元数据保存失败");
        }
        workflow.setWorkflowStatus(WorkflowStatus.COMPLETED);
        workflow.setFinishTime(LocalDateTime.now());
        workflow.setUpdatedAt(LocalDateTime.now());
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        if (workflowMapper.updateById(workflow) != 1) {
            throw new IllegalStateException("CFS report workflow completion update failed");
        }
        return new ExportResult(cfsArtifactId, List.copyOf(files));
    }

    @Transactional
    public void recordFailure(String workflowId, String message) {
        WorkflowState workflow = requireWorkflow(workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.GENERATING_OUTPUT) {
            return;
        }
        String failureMessage = StringUtils.hasText(message) ? message : "CFS report export failed";
        workflow.setErrorCode("CFS_REPORT_EXPORT_FAILED");
        workflow.setErrorMessage(failureMessage.substring(0, Math.min(failureMessage.length(), 1000)));
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflowMapper.updateById(workflow) != 1) {
            throw new IllegalStateException("CFS report workflow failure update failed");
        }
    }

    private WorkflowState requireWorkflow(String workflowId) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalStateException("Workflow does not exist: " + workflowId);
        }
        return workflow;
    }
    private boolean isHumanReviewableCompliance(String complianceResult) {
        return "PASS".equalsIgnoreCase(complianceResult)
                || "REVIEW_REQUIRED".equalsIgnoreCase(complianceResult);
    }


    private AgentArtifact requireArtifact(String artifactId, String workflowId, AgentType type) {
        AgentArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null
                || !workflowId.equals(artifact.getWorkflowId())
                || artifact.getAgentType() != type) {
            throw new IllegalStateException(type + " Artifact不存在或不属于当前工作流");
        }
        return artifact;
    }

    private ObjectNode objectNode(String value, String message) {
        JsonNode node = json(value, message);
        if (!node.isObject()) {
            throw new IllegalStateException(message);
        }
        return (ObjectNode) node;
    }

    private JsonNode json(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private ArrayNode mergeFiles(JsonNode existing, List<FileMetadata> generated) {
        ArrayNode merged = objectMapper.createArrayNode();
        if (existing.isArray()) {
            existing.forEach(file -> {
                if (!GENERATOR.equals(file.path("generator").asText())) {
                    merged.add(file.deepCopy());
                }
            });
        }
        generated.forEach(file -> merged.add(objectMapper.valueToTree(file)));
        return merged;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("CFS报告文件元数据序列化失败", exception);
        }
    }

    private String reportBaseName(CfsReportDocument report) {
        String customer = StringUtils.hasText(report.customerId()) ? report.customerId() : "UNKNOWN";
        return "CFS报告_" + customer + "_V" + report.cfsVersion();
    }

    private String fileId(String artifactId, Format format) {
        String normalized = artifactId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String suffix = normalized.length() > 20
                ? normalized.substring(normalized.length() - 20)
                : normalized;
        return "FILE-" + suffix + "-" + format.name();
    }

    private enum Format {
        MARKDOWN("text/markdown;charset=UTF-8"),
        WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        PDF("application/pdf");

        private final String contentType;

        Format(String contentType) {
            this.contentType = contentType;
        }
    }

    private record RenderedReport(Format format, String fileName, byte[] content) {
        private RenderedReport {
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException(format + "报告内容为空");
            }
        }
    }

    public record FileMetadata(
            String fileId,
            String format,
            String fileName,
            String contentType,
            long sizeBytes,
            String path,
            String generatedAt,
            String complianceArtifactId,
            String generator) {
    }

    public record ExportResult(String cfsArtifactId, List<FileMetadata> files) {
    }
}
