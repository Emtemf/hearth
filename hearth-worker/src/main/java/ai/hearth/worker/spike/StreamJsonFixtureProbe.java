package ai.hearth.worker.spike;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class StreamJsonFixtureProbe {
    private final Path fixture;
    private final Duration timeout;

    StreamJsonFixtureProbe(Path fixture, Duration timeout) {
        this.fixture = fixture;
        this.timeout = timeout;
    }

    FixtureProbeResult run() throws IOException, InterruptedException, ProtocolException {
        var process = new ProcessBuilder("sh", fixture.toString()).start();
        try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write("{\"type\":\"user\",\"message\":\"first\"}\n");
            writer.flush();
            writer.write("{\"type\":\"user\",\"message\":\"second\"}\n");
            writer.flush();
        }
        var events = new NdjsonEventReader(16 * 1024).readAll(process.getInputStream());
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("worker.fixture_probe.timeout");
        }
        var types = events.stream().map(NdjsonEvent::type).toList();
        var policy = types.equals(java.util.List.of("system", "assistant", "result", "assistant", "result"))
                ? ProcessReusePolicy.STREAMING_STDIN
                : ProcessReusePolicy.RESUME_PER_INVOCATION;
        return new FixtureProbeResult(policy, types, process.exitValue());
    }
}
