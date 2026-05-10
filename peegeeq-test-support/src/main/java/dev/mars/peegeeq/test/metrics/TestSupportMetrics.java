package dev.mars.peegeeq.test.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lightweight metrics helper for peegeeq-test-support.
 *
 * <p>This intentionally avoids dependency on peegeeq-db while keeping
 * the test APIs used by shared test base classes and demos.</p>
 */
public class TestSupportMetrics {

    private final MeterRegistry registry;
    private final String instanceId;

    public TestSupportMetrics(MeterRegistry registry, String instanceId) {
        this.registry = registry;
        this.instanceId = instanceId;
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void incrementCounter(String name, Map<String, String> tags) {
        Counter.builder(name)
            .tags(toTags(tags))
            .tag("instance", instanceId)
            .register(registry)
            .increment();
    }

    public void recordGauge(String name, double value, Map<String, String> tags) {
        Gauge.builder(name, () -> value)
            .tags(toTags(tags))
            .tag("instance", instanceId)
            .register(registry);
    }

    public void recordTimer(String name, long durationMs, Map<String, String> tags) {
        Timer.builder(name)
            .tags(toTags(tags))
            .tag("instance", instanceId)
            .register(registry)
            .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    public void recordPerformanceTestExecution(String testName,
                                               String profile,
                                               long durationMs,
                                               boolean success,
                                               double throughput,
                                               Map<String, Object> additionalMetrics) {
        Map<String, String> executionTags = Map.of(
            "test_name", testName,
            "profile", profile,
            "success", String.valueOf(success)
        );

        recordTimer("peegeeq.test.performance.execution", durationMs, executionTags);

        Counter.builder(success ? "peegeeq.test.performance.success" : "peegeeq.test.performance.failure")
            .tags(toTags(executionTags))
            .tag("instance", instanceId)
            .register(registry)
            .increment();

        Gauge.builder("peegeeq.test.performance.throughput", () -> throughput)
            .tags(toTags(executionTags))
            .tag("instance", instanceId)
            .register(registry);

        if (additionalMetrics != null) {
            for (Map.Entry<String, Object> entry : additionalMetrics.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number) {
                    String metricName = "peegeeq.test.performance." + entry.getKey();
                    Gauge.builder(metricName, () -> ((Number) value).doubleValue())
                        .tags(toTags(executionTags))
                        .tag("instance", instanceId)
                        .register(registry);
                }
            }
        }
    }

    private Iterable<Tag> toTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        List<Tag> converted = new ArrayList<>(tags.size());
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            converted.add(Tag.of(entry.getKey(), entry.getValue()));
        }
        return converted;
    }
}
