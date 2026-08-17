package ai.hearth.worker.runtime;

import java.nio.file.Path;
import java.util.UUID;
import ai.hearth.worker.spike.ChildEnvironment;
import ai.hearth.worker.spike.ClaudeCodeCommand;

public interface WorkerClient {
    WorkerCommandResult launch(LaunchCommand command);
    WorkerCommandResult invoke(InvokeCommand command);
    WorkerCommandResult cancel(CancelCommand command);
}

record ProcessIdentity(UUID sessionId, long processGeneration, UUID launchId) {}

record LaunchCommand(UUID commandId, ProcessIdentity process, Path executable, Path workingDirectory) {}

record InvokeCommand(UUID commandId, UUID invocationId, ProcessIdentity process, String content) {}

record CancelCommand(UUID commandId, ProcessIdentity process) {}

record WorkerCommandResult(UUID commandId, String state, ProcessIdentity process, String detail) {}
