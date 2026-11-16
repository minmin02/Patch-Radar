package io.patchradar.release.api;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.patchradar.release.store.Release;
import jakarta.annotation.PreDestroy;

@Component
public class SseHub {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-ping");
        t.setDaemon(true);
        return t;
    });

    public SseHub() {
        pinger.scheduleAtFixedRate(() -> broadcast(SseEmitter.event().comment("ping")), 30, 30, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected")); // 헤더를 바로 내보낸다
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(Release.Summary summary) {
        broadcast(SseEmitter.event().name("release").data(summary));
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter e : emitters) {
            try {
                e.send(event);
            } catch (Exception ex) { // 끊긴 연결
                emitters.remove(e);
            }
        }
    }

    @PreDestroy
    void stop() {
        pinger.shutdownNow();
    }
}
