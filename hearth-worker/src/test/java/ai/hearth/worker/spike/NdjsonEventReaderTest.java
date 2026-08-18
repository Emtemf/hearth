package ai.hearth.worker.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class NdjsonEventReaderTest {

    @Test
    void givenTwoValidEvents_whenRead_thenReturnsTheirTypesInOrder() throws Exception {
        var input = new ByteArrayInputStream(("{\"type\":\"system\"}\n"
                + "{\"type\":\"result\"}\n").getBytes(StandardCharsets.UTF_8));

        var events = new NdjsonEventReader(1024).readAll(input);

        assertThat(events).extracting(NdjsonEvent::type).containsExactly("system", "result");
    }

    @Test
    void givenNonJsonLine_whenRead_thenRejectsWithoutEchoingContent() {
        var input = new ByteArrayInputStream("not-json-secret-value\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new NdjsonEventReader(1024).readAll(input))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("worker.ndjson.invalid_json");
    }

    @Test
    void givenJsonArray_whenRead_thenRejectsItAsNonObject() {
        var input = new ByteArrayInputStream("[]\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new NdjsonEventReader(1024).readAll(input))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("worker.ndjson.expected_object");
    }

    @Test
    void givenEventWithoutType_whenRead_thenRejectsIt() {
        var input = new ByteArrayInputStream("{\"message\":\"missing type\"}\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new NdjsonEventReader(1024).readAll(input))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("worker.ndjson.missing_type");
    }

    @Test
    void givenOversizedLine_whenRead_thenRejectsIt() {
        var input = new ByteArrayInputStream("{\"type\":\"123456789\"}\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new NdjsonEventReader(16).readAll(input))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("worker.ndjson.line_too_large");
    }
}
