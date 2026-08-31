package com.privatebank.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBusinessBoundaryTest {

    private static final List<String> FORBIDDEN_AGENT_DEPENDENCIES = List.of(
            "com.privatebank.business.mapper.workflow",
            "com.privatebank.business.entity.workflow",
            "com.privatebank.business.service.workflow");

    @Test
    void agentDoesNotAccessWorkflowPersistenceOrOrchestrationImplementation() throws IOException {
        Path agentRoot = Path.of("src/main/java/com/privatebank/agent");
        try (var files = Files.walk(agentRoot)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenImports(path).stream())
                    .toList();

            assertThat(violations)
                    .as("Agent must consume runtime contracts, not Workflow implementation")
                    .isEmpty();
        }
    }

    private List<String> forbiddenImports(Path path) {
        try {
            String source = Files.readString(path);
            return FORBIDDEN_AGENT_DEPENDENCIES.stream()
                    .filter(source::contains)
                    .map(dependency -> path + " -> " + dependency)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取架构测试文件: " + path, exception);
        }
    }
}
