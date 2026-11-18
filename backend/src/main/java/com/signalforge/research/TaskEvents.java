package com.signalforge.research;

import com.signalforge.platform.ProjectAccess;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class TaskEvents {
    private record Subscription(UUID project, UUID task, AtomicLong after, Authentication auth, SseEmitter emitter, Instant until) {}
    private final Map<UUID,Subscription> subscriptions=new ConcurrentHashMap<>();
    private final TaskStore store;
    private final ProjectAccess access;
    public TaskEvents(TaskStore store, ProjectAccess access) { this.store=store; this.access=access; }
    public SseEmitter subscribe(UUID project, UUID task, long after, Authentication auth) {
        UUID id=UUID.randomUUID(); SseEmitter emitter=new SseEmitter(55_000L);
        Subscription sub=new Subscription(project,task,new AtomicLong(after),auth,emitter,Instant.now().plusSeconds(50));
        emitter.onCompletion(()->subscriptions.remove(id)); emitter.onTimeout(()->subscriptions.remove(id));
        emitter.onError(e->subscriptions.remove(id)); subscriptions.put(id,sub);
        return emitter;
    }
    @Scheduled(fixedDelay=500)
    void publish() {
        subscriptions.forEach((id,s)->{
            try {
                access.require(s.project(),s.auth(),false);
                for (var e:store.events(s.project(),s.task(),s.after().get())) {
                    s.emitter().send(SseEmitter.event().id(Long.toString(e.sequence())).name(e.type()).data(e));
                    s.after().set(e.sequence());
                }
                TaskStore.Task task=store.get(s.project(),s.task());
                if ((task.terminal() && s.after().get()>=task.eventSequence()) || Instant.now().isAfter(s.until())) {
                    subscriptions.remove(id); s.emitter().complete();
                }
            } catch (Exception e) { subscriptions.remove(id); s.emitter().completeWithError(e); }
        });
    }
}
