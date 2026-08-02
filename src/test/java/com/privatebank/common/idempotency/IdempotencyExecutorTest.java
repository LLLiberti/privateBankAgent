package com.privatebank.common.idempotency;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyExecutorTest {

    @Test
    void concurrentRequestsWithSameKeyExecuteBusinessActionOnce() throws Exception {
        IdempotencyExecutor executor = new IdempotencyExecutor(180);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> request = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return executor.execute("same-business-key", () -> {
                executions.incrementAndGet();
                return "WF-001";
            });
        };

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<String>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> pool.submit(request))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("WF-001");
            }
        }

        assertThat(executions).hasValue(1);
    }

    @Test
    void failedActionCanBeRetriedWithSameKey() {
        IdempotencyExecutor executor = new IdempotencyExecutor(180);
        AtomicInteger attempts = new AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute("retry-key", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary failure");
        })).isInstanceOf(IllegalStateException.class);

        String result = executor.execute("retry-key", () -> {
            attempts.incrementAndGet();
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(attempts).hasValue(2);
    }
}
