package ai.hearth.worker.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NdjsonEventReader {
    private static final Pattern TYPE = Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final int maximumLineBytes;

    NdjsonEventReader(int maximumLineBytes) {
        if (maximumLineBytes < 1) {
            throw new IllegalArgumentException("worker.ndjson.maximum_line_bytes.invalid");
        }
        this.maximumLineBytes = maximumLineBytes;
    }

    List<NdjsonEvent> readAll(InputStream inputStream) throws IOException, ProtocolException {
        var events = new ArrayList<NdjsonEvent>();
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.getBytes(StandardCharsets.UTF_8).length > maximumLineBytes) {
                    throw new ProtocolException("worker.ndjson.line_too_large");
                }
                events.add(parse(line));
            }
        }
        return List.copyOf(events);
    }

    private NdjsonEvent parse(String line) throws ProtocolException {
        var trimmed = line.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            if (trimmed.startsWith("[") || trimmed.startsWith("\"")) {
                throw new ProtocolException("worker.ndjson.expected_object");
            }
            throw new ProtocolException("worker.ndjson.invalid_json");
        }
        Matcher matcher = TYPE.matcher(trimmed);
        if (!matcher.find()) {
            throw new ProtocolException("worker.ndjson.missing_type");
        }
        return new NdjsonEvent(matcher.group(1), trimmed);
    }
}
