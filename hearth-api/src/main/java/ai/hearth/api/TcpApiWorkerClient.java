package ai.hearth.api;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hearth.worker.transport", havingValue = "tcp")
final class TcpApiWorkerClient implements ApiWorkerClient {
    private final String host;
    private final int port;
    private final String controlToken;

    TcpApiWorkerClient(
            @Value("${hearth.worker.host:127.0.0.1}") String host,
            @Value("${hearth.worker.port:4617}") int port,
            @Value("${hearth.worker.control-token:}") String controlToken) {
        this.host = host;
        this.port = port;
        this.controlToken = controlToken;
    }

    @Override
    public WorkerInvocationResult invoke(UUID sessionId, UUID invocationId, String content, String model, String systemPrompt) {
        UUID launchId = UUID.nameUUIDFromBytes((sessionId + ":launch").getBytes(StandardCharsets.UTF_8));
        UUID commandId = UUID.nameUUIDFromBytes((invocationId + ":invoke").getBytes(StandardCharsets.UTF_8));
        try {
            var launch = request("action=launch", sessionId, 1, launchId, commandId, null, null);
            if (!launch.startsWith("COMMITTED")) return new WorkerInvocationResult("", launch);
            var result = request("action=invoke", sessionId, 1, launchId, commandId, invocationId, content);
            var assistant = result.startsWith("EVENT") ? decodeEventContent(result) : result;
            return new WorkerInvocationResult(assistant, "worker_connected");
        } catch (IOException exception) {
            return new WorkerInvocationResult("", "worker_transport_failed");
        }
    }

    @Override
    public WorkerCancellationResult cancel(UUID invocationId) {
        return new WorkerCancellationResult("CANCELLING", "worker_cancel_queued");
    }

    private String decodeEventContent(String line) {
        for (var part : line.split("\\t")) {
            if (part.startsWith("content=")) return new String(Base64.getDecoder().decode(part.substring(8)), StandardCharsets.UTF_8);
        }
        return "";
    }

    private String request(String action, UUID sessionId, long generation, UUID launchId, UUID commandId,
            UUID invocationId, String content) throws IOException {
        var line = new StringBuilder(action)
                .append("\ttoken=").append(controlToken)
                .append("\tsessionId=").append(sessionId)
                .append("\tgeneration=").append(generation)
                .append("\tlaunchId=").append(launchId)
                .append("\tcommandId=").append(commandId);
        if (invocationId != null) line.append("\tinvocationId=").append(invocationId);
        if (content != null) line.append("\tcontent=").append(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(120_000);
            try (var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(line + "\n");
                writer.flush();
                return reader.readLine();
            }
        }
    }
}
