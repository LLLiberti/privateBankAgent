package com.privatebank.business.service.workflow;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WorkflowEventHub {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String workflowId) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        subscribers.computeIfAbsent(workflowId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(workflowId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event()
                    .id(nextId(workflowId))
                    .name("CONNECTED")
                    .data(Map.of("workflowId", workflowId, "eventTime", Instant.now().toString())));
        } catch (IOException exception) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(String workflowId, String eventName, Object payload) {
        for (SseEmitter emitter : subscribers.getOrDefault(workflowId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().id(nextId(workflowId)).name(eventName).data(payload));
            } catch (IOException exception) {
                remove(workflowId, emitter);
            }
        }
    }

    private String nextId(String workflowId) {
        return Long.toString(sequences.computeIfAbsent(workflowId, ignored -> new AtomicLong()).incrementAndGet());
    }

    private void remove(String workflowId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(workflowId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                subscribers.remove(workflowId, emitters);
            }
        }
    }
}
