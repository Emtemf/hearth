package ai.hearth.worker.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

public final class WorkerDaemonApplication {
    private WorkerDaemonApplication() {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(required("HEARTH_WORKER_PORT"));
        String controlToken = required("HEARTH_WORKER_CONTROL_TOKEN");
        Path executable = Path.of(required("HEARTH_CLAUDE_EXECUTABLE"));
        Path workingDirectory = Path.of(required("HEARTH_WORKER_WORKING_DIRECTORY"));
        String gatewayUrl = required("HEARTH_GATEWAY_URL");
        String gatewayCapability = required("HEARTH_GATEWAY_CAPABILITY");
        try (var daemon = new LocalWorkerDaemon(executable, workingDirectory, gatewayUrl, gatewayCapability);
                var server = new ServerSocket(port)) {
            while (true) {
                try (Socket socket = server.accept()) {
                    handle(socket, daemon, controlToken);
                }
            }
        }
    }

    private static void handle(Socket socket, LocalWorkerDaemon daemon, String controlToken) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) return;
            var fields = new java.util.HashMap<String, String>();
            for (var entry : parse(line)) fields.put(entry.getKey(), entry.getValue());
            if (!controlToken.equals(fields.get("token"))) {
                writer.write("REJECTED_BEFORE_COMMIT\tworker.control_token_invalid\n");
                writer.flush();
                return;
            }
            UUID sessionId = UUID.fromString(fields.get("sessionId"));
            long generation = Long.parseLong(fields.get("generation"));
            UUID launchId = UUID.fromString(fields.get("launchId"));
            UUID commandId = UUID.fromString(fields.get("commandId"));
            WorkerCommandResult result = switch (fields.get("action")) {
                case "launch" -> daemon.launch(sessionId, generation, launchId, commandId);
                case "invoke" -> daemon.invoke(sessionId, generation, launchId,
                        UUID.fromString(fields.get("invocationId")), commandId,
                        new String(Base64.getDecoder().decode(fields.get("content")), StandardCharsets.UTF_8));
                case "cancel" -> daemon.cancel(sessionId, generation, launchId, commandId);
                default -> throw new IllegalArgumentException("worker.action_unknown");
            };
            writer.write(result.state() + "\t" + result.detail() + "\n");
            writer.flush();
            if ("invoke".equals(fields.get("action"))) {
                streamEvents(socket, daemon, sessionId, UUID.fromString(fields.get("invocationId")));
            }
        } catch (IllegalArgumentException exception) {
            socket.getOutputStream().write("REJECTED_BEFORE_COMMIT\tworker.request_invalid\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void streamEvents(Socket socket, LocalWorkerDaemon daemon, UUID sessionId, UUID invocationId) {
        daemon.events(sessionId).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(WorkerEvent event) {
                try {
                    var content = Base64.getEncoder().encodeToString(event.content().getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(("EVENT\ttype=" + event.type() + "\tcontent=" + content + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) { }
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });
    }

    private static Map.Entry<String, String>[] parse(String line) {
        return java.util.Arrays.stream(line.split("\\t"))
                .map(part -> part.split("=", 2))
                .map(pair -> Map.entry(pair[0], pair.length == 2 ? pair[1] : ""))
                .toArray(Map.Entry[]::new);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
