package ai.hearth.worker.spike;

import java.util.List;

record FixtureProbeResult(ProcessReusePolicy reusePolicy, List<String> eventTypes, int exitCode) {}
