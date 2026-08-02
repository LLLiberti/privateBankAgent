package com.privatebank.common.idempotency;

import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class IdempotencyExecutor {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public IdempotencyExecutor(@Value("${private-bank.idempotency.ttl-minutes:180}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public <T> T execute(String businessKey, Supplier<T> action) {
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 256) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "Idempotency-Key不能为空且不能超过256字符");
        }
        evictExpired();
        Entry candidate = new Entry(Instant.now(), new CompletableFuture<>());
        Entry existing = entries.putIfAbsent(businessKey, candidate);
        Entry selected = existing == null ? candidate : existing;
        if (existing == null) {
            try {
                T result = action.get();
                selected.result().complete(result);
            } catch (Throwable throwable) {
                selected.result().completeExceptionally(throwable);
                entries.remove(businessKey, selected);
            }
        }
        try {
            @SuppressWarnings("unchecked")
            T result = (T) selected.result().join();
            return result;
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private void evictExpired() {
        Instant deadline = Instant.now().minus(ttl);
        entries.entrySet().removeIf(entry ->
                entry.getValue().createdAt().isBefore(deadline) && entry.getValue().result().isDone());
    }

    private record Entry(Instant createdAt, CompletableFuture<Object> result) {
    }
}
