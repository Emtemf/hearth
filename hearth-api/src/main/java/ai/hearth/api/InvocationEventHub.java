package ai.hearth.api;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hearth.persistence.enabled", havingValue = "true")
final class InvocationEventHub {
    private final CopyOnWriteArrayList<SubmissionPublisher<InvocationEvent>> subscribers = new CopyOnWriteArrayList<>();

    Flow.Publisher<InvocationEvent> subscribe() {
        var publisher = new SubmissionPublisher<InvocationEvent>();
        subscribers.add(publisher);
        return subscriber -> publisher.subscribe(new AutoClosingSubscriber(subscriber, publisher, subscribers));
    }

    void publish(InvocationEvent event) {
        subscribers.forEach(publisher -> publisher.submit(event));
    }

    private static final class AutoClosingSubscriber implements Flow.Subscriber<InvocationEvent> {
        private final Flow.Subscriber<? super InvocationEvent> delegate;
        private final SubmissionPublisher<InvocationEvent> publisher;
        private final CopyOnWriteArrayList<SubmissionPublisher<InvocationEvent>> subscribers;

        private AutoClosingSubscriber(
                Flow.Subscriber<? super InvocationEvent> delegate,
                SubmissionPublisher<InvocationEvent> publisher,
                CopyOnWriteArrayList<SubmissionPublisher<InvocationEvent>> subscribers) {
            this.delegate = delegate;
            this.publisher = publisher;
            this.subscribers = subscribers;
        }

        @Override public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
        @Override public void onNext(InvocationEvent item) { delegate.onNext(item); }
        @Override public void onError(Throwable throwable) { subscribers.remove(publisher); delegate.onError(throwable); }
        @Override public void onComplete() { subscribers.remove(publisher); delegate.onComplete(); }
    }
}

record InvocationEvent(UUID invocationId, UUID sessionId, String status, String assistantContent) {}
