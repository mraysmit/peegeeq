package dev.mars.peegeeq.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.HTTPServer;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicDouble;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardware Profiling Metrics Generator for Demo
 * Generates realistic hardware profiling metrics for Grafana dashboard demonstration
 */
public class HardwareProfilingMetricsGenerator {
    
    private final MeterRegistry meterRegistry;
    private final HTTPServer server;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();
    
    // Hardware info
    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GlobalMemory memory;
    
    // Simulated metrics
    private final AtomicDouble cpuUsage = new AtomicDouble(0.0);
    private final AtomicDouble memoryUsage = new AtomicDouble(0.0);
    private final AtomicDouble jvmMemoryUsage = new AtomicDouble(0.0);
    private final AtomicDouble systemLoad = new AtomicDouble(0.0);
    private final AtomicInteger threadCount = new AtomicInteger(0);
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesProcessed = new AtomicInteger(0);
    
    public HardwareProfilingMetricsGenerator() throws IOException {
        this.meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.server = new HTTPServer(8080);
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // Initialize hardware info
        this.systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
        this.processor = hardware.getProcessor();
        this.memory = hardware.getMemory();
        
        setupMetrics();
        startMetricsGeneration();
    }
    
    private void setupMetrics() {
        String instanceId = "demo-instance-" + System.currentTimeMillis();
        String hardwareProfileHash = calculateHardwareProfileHash();
        
        // Hardware specification metrics
        Gauge.builder("peegeeq_hardware_cpu_cores", () -> processor.getLogicalProcessorCount())
            .description("Number of CPU cores")
            .tag("instance", instanceId)
            .tag("hardware_profile_hash", hardwareProfileHash)
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_hardware_cpu_frequency_ghz", () -> processor.getMaxFreq() / 1_000_000_000.0)
            .description("CPU frequency in GHz")
            .tag("instance", instanceId)
            .tag("hardware_profile_hash", hardwareProfileHash)
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_hardware_memory_total_gb", () -> memory.getTotal() / (1024.0 * 1024.0 * 1024.0))
            .description("Total memory in GB")
            .tag("instance", instanceId)
            .tag("hardware_profile_hash", hardwareProfileHash)
            .register(meterRegistry);
            
        // JVM metrics
        Runtime runtime = Runtime.getRuntime();
        Gauge.builder("peegeeq_hardware_jvm_max_heap_gb", () -> runtime.maxMemory() / (1024.0 * 1024.0 * 1024.0))
            .description("JVM max heap in GB")
            .tag("instance", instanceId)
            .tag("hardware_profile_hash", hardwareProfileHash)
            .register(meterRegistry);
            
        // Real-time resource usage metrics
        Gauge.builder("peegeeq_test_cpu_usage_percent", cpuUsage::get)
            .description("Real-time CPU usage during tests")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_test_memory_usage_percent", memoryUsage::get)
            .description("Real-time memory usage during tests")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_test_jvm_memory_usage_percent", jvmMemoryUsage::get)
            .description("Real-time JVM memory usage during tests")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_test_system_load", systemLoad::get)
            .description("System load during tests")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_test_thread_count", threadCount::get)
            .description("Thread count during tests")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        // Performance metrics
        Gauge.builder("peegeeq_performance_throughput_per_cpu_core", () -> {
            double throughput = messagesProcessed.get() / 60.0; // messages per second
            return throughput / processor.getLogicalProcessorCount();
        })
            .description("Throughput per CPU core")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .tag("hardware_profile_hash", hardwareProfileHash)
            .register(meterRegistry);
            
        // Resource constraint indicators
        Gauge.builder("peegeeq_performance_has_resource_constraints", () -> {
            return (cpuUsage.get() > 90 || memoryUsage.get() > 85) ? 1.0 : 0.0;
        })
            .description("Resource constraints indicator")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_performance_has_high_resource_usage", () -> {
            return (cpuUsage.get() > 70 || memoryUsage.get() > 70) ? 1.0 : 0.0;
        })
            .description("High resource usage indicator")
            .tag("instance", instanceId)
            .tag("test_name", "DemoPerformanceTest")
            .register(meterRegistry);
            
        // Message throughput counters
        Counter.builder("peegeeq_messages_sent_total")
            .description("Total messages sent")
            .tag("instance", instanceId)
            .register(meterRegistry);
            
        Counter.builder("peegeeq_messages_processed_total")
            .description("Total messages processed")
            .tag("instance", instanceId)
            .register(meterRegistry);
            
        // Queue depth gauges
        Gauge.builder("peegeeq_queue_depth_outbox", () -> random.nextInt(100))
            .description("Outbox queue depth")
            .tag("instance", instanceId)
            .register(meterRegistry);
            
        Gauge.builder("peegeeq_queue_depth_native", () -> random.nextInt(50))
            .description("Native queue depth")
            .tag("instance", instanceId)
            .register(meterRegistry);
    }
    
    private void startMetricsGeneration() {
        // Update resource usage metrics every 5 seconds
        scheduler.scheduleAtFixedRate(this::updateResourceMetrics, 0, 5, TimeUnit.SECONDS);
        
        // Simulate message processing every second
        scheduler.scheduleAtFixedRate(this::simulateMessageProcessing, 0, 1, TimeUnit.SECONDS);
        
        System.out.println("🎯 Hardware Profiling Metrics Generator started");
        System.out.println("📊 Metrics available at: http://localhost:8080/metrics");
        System.out.println("🔧 Hardware Profile: " + getHardwareProfileSummary());
    }
    
    private void updateResourceMetrics() {
        // Simulate realistic resource usage patterns
        double baseCpu = 20.0 + random.nextGaussian() * 10.0;
        double baseMemory = 60.0 + random.nextGaussian() * 15.0;
        double baseJvmMemory = 30.0 + random.nextGaussian() * 10.0;
        double baseSystemLoad = 1.0 + random.nextGaussian() * 0.5;
        int baseThreads = 20 + random.nextInt(10);
        
        // Add occasional spikes to simulate test workloads
        if (random.nextDouble() < 0.3) { // 30% chance of spike
            baseCpu += random.nextDouble() * 40.0;
            baseMemory += random.nextDouble() * 20.0;
            baseJvmMemory += random.nextDouble() * 30.0;
            baseSystemLoad += random.nextDouble() * 2.0;
            baseThreads += random.nextInt(20);
        }
        
        // Ensure realistic bounds
        cpuUsage.set(Math.max(0, Math.min(100, baseCpu)));
        memoryUsage.set(Math.max(0, Math.min(100, baseMemory)));
        jvmMemoryUsage.set(Math.max(0, Math.min(100, baseJvmMemory)));
        systemLoad.set(Math.max(0, baseSystemLoad));
        threadCount.set(Math.max(1, baseThreads));
    }
    
    private void simulateMessageProcessing() {
        // Simulate message throughput
        int sent = random.nextInt(100) + 50;
        int processed = random.nextInt(90) + 40;
        
        messagesSent.addAndGet(sent);
        messagesProcessed.addAndGet(processed);
    }
    
    private String calculateHardwareProfileHash() {
        String profile = processor.getProcessorIdentifier().getName() + 
                        "_" + processor.getLogicalProcessorCount() + 
                        "_" + memory.getTotal();
        return "hw_" + Math.abs(profile.hashCode()) % 10000;
    }
    
    private String getHardwareProfileSummary() {
        return String.format("%s | %d cores @ %.1f GHz | %.1f GB RAM",
            processor.getProcessorIdentifier().getName(),
            processor.getLogicalProcessorCount(),
            processor.getMaxFreq() / 1_000_000_000.0,
            memory.getTotal() / (1024.0 * 1024.0 * 1024.0)
        );
    }
    
    public void stop() {
        scheduler.shutdown();
        server.stop();
        System.out.println("🛑 Hardware Profiling Metrics Generator stopped");
    }
    
    public static void main(String[] args) {
        try {
            HardwareProfilingMetricsGenerator generator = new HardwareProfilingMetricsGenerator();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(generator::stop));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("❌ Failed to start metrics generator: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
