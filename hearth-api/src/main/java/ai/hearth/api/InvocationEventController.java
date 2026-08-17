package ai.hearth.api;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/sessions")
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InvocationEventController {
    private final InvocationEventHub eventHub;
    private final JsonMapper mapper = JsonMapper.builder().build();

    InvocationEventController(InvocationEventHub eventHub) {
        this.eventHub = eventHub;
    }

    @GetMapping(value = "/{sessionId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable("sessionId") UUID sessionId) {
        var emitter = new SseEmitter(0L);
        eventHub.subscribe().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(InvocationEvent event) {
                if (!event.sessionId().equals(sessionId)) return;
                try { emitter.send(SseEmitter.event().name("invocation").data(mapper.valueToTree(event).toString())); }
                catch (IOException exception) { emitter.completeWithError(exception); }
            }
            @Override public void onError(Throwable throwable) { emitter.completeWithError(throwable); }
            @Override public void onComplete() { emitter.complete(); }
        });
        emitter.onCompletion(() -> emitter.complete());
        return emitter;
    }
}
