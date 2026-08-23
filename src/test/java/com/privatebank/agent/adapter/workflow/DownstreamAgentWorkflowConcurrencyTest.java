package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.service.workflow.DownstreamAgentsReadyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class DownstreamAgentWorkflowConcurrencyTest {

    @Test
    void executesMarketAndProductAgentsInParallel() throws Exception {
        DownstreamAgentExecutionService executionService = mock(DownstreamAgentExecutionService.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(executionService).executeMarketInsight("WF-1", "ART-KYC-2");
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(executionService).executeProductExpert("WF-1", "ART-KYC-2");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DownstreamAgentExecutionService.class, () -> executionService);
            context.register(KycAsyncConfiguration.class, DownstreamAgentWorkflowListener.class);
            context.refresh();

            try {
                context.getBean(DownstreamAgentWorkflowListener.class).onDownstreamAgentsReady(
                        new DownstreamAgentsReadyEvent("WF-1", "ART-KYC-2",
                                List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT)));

                assertTrue(started.await(5, TimeUnit.SECONDS),
                        "Both downstream agents should start before either one completes");
            } finally {
                release.countDown();
            }
        }
    }
}
