# PeeGeeQ Hardware-Aware Performance Dashboard Guide

**Author**: Mark Andrew Ray-Smith Cityline Ltd  
**Date**: September 2025  
**Version**: 1.0  

## Overview

The PeeGeeQ Hardware-Aware Performance Dashboard provides comprehensive visualization of performance testing results with full hardware context. This dashboard integrates the Phase 2.1.5 hardware profiling infrastructure with Grafana to deliver meaningful, reproducible performance insights.

## Key Features

### 🔧 **Hardware Profile Visualization**
- **System Specifications**: CPU cores, memory, frequency display
- **JVM Configuration**: Heap settings, container limits
- **Cross-Hardware Comparison**: Performance metrics grouped by hardware profile

### 📊 **Real-Time Resource Monitoring**
- **CPU Usage**: Real-time CPU utilization during test execution
- **Memory Tracking**: System and JVM memory usage correlation
- **System Load**: Load average and thread count monitoring
- **Resource Constraints**: Automated detection and alerting

### 🚀 **Performance Efficiency Metrics**
- **Throughput per CPU Core**: Hardware-normalized performance metrics
- **Resource Utilization Efficiency**: Optimal resource usage identification
- **Performance Regression Detection**: Cross-hardware performance comparison

## Dashboard Panels

### 1. **Hardware Profile Overview**
- **Metrics**: `peegeeq_hardware_cpu_cores`, `peegeeq_hardware_memory_total_gb`, `peegeeq_hardware_cpu_frequency_ghz`
- **Purpose**: Display current system specifications for test context
- **Usage**: Verify test environment and compare across different hardware

### 2. **JVM Configuration**
- **Metrics**: `peegeeq_hardware_jvm_max_heap_gb`, `peegeeq_hardware_container_memory_limit_gb`
- **Purpose**: Show JVM and container configuration
- **Usage**: Ensure consistent JVM settings across test environments

### 3. **Real-Time CPU Usage During Tests**
- **Metrics**: `peegeeq_test_cpu_usage_percent`
- **Purpose**: Monitor CPU utilization during test execution
- **Usage**: Identify CPU bottlenecks and resource constraints

### 4. **Real-Time Memory Usage During Tests**
- **Metrics**: `peegeeq_test_memory_usage_percent`, `peegeeq_test_jvm_memory_usage_percent`
- **Purpose**: Track system and JVM memory usage
- **Usage**: Detect memory pressure and optimize heap settings

### 5. **System Load and Thread Count**
- **Metrics**: `peegeeq_test_system_load`, `peegeeq_test_thread_count`
- **Purpose**: Monitor system load and thread utilization
- **Usage**: Identify system saturation and threading issues

### 6. **Performance Efficiency by Hardware**
- **Metrics**: `peegeeq_performance_throughput_per_cpu_core`
- **Purpose**: Hardware-normalized performance comparison
- **Usage**: Compare performance across different hardware configurations

### 7. **Resource Constraint Indicators**
- **Metrics**: `peegeeq_performance_has_resource_constraints`, `peegeeq_performance_has_high_resource_usage`
- **Purpose**: Visual indicators of resource bottlenecks
- **Usage**: Quickly identify tests affected by resource limitations

### 8. **Message Throughput (Hardware Context)**
- **Metrics**: `rate(peegeeq_messages_sent_total[5m])`, `rate(peegeeq_messages_processed_total[5m])`
- **Purpose**: Traditional throughput metrics with hardware context
- **Usage**: Correlate message processing with hardware specifications

### 9. **Queue Depth vs Hardware Profile**
- **Metrics**: `peegeeq_queue_depth_outbox`, `peegeeq_queue_depth_native`
- **Purpose**: Queue performance correlation with hardware
- **Usage**: Optimize queue configuration for different hardware profiles

## Template Variables

### **Environment**
- **Query**: `label_values(peegeeq_hardware_cpu_cores, environment)`
- **Purpose**: Filter by test environment (test, staging, production)

### **Instance**
- **Query**: `label_values(peegeeq_hardware_cpu_cores{environment="$environment"}, instance)`
- **Purpose**: Select specific test instances

### **Hardware Profile**
- **Query**: `label_values(peegeeq_hardware_cpu_cores{environment="$environment"}, hardware_profile_hash)`
- **Purpose**: Filter by hardware profile for cross-hardware comparison

## Annotations

### **Test Executions**
- **Query**: `changes(peegeeq_test_cpu_usage_percent[1m])`
- **Purpose**: Mark test start/end times on graphs
- **Display**: Test name and hardware profile hash

### **Resource Constraints**
- **Query**: `peegeeq_performance_has_resource_constraints == 1`
- **Purpose**: Highlight tests that experienced resource constraints
- **Display**: Warning annotations for affected tests

## Setup Instructions

### 1. **Import Dashboard**
```bash
# Import the dashboard JSON into Grafana
curl -X POST \
  http://grafana:3000/api/dashboards/db \
  -H 'Content-Type: application/json' \
  -d @peegeeq-examples/src/main/resources/grafana-dashboard-hardware-profiling.json
```

### 2. **Configure Prometheus**
```yaml
# Add to prometheus.yml
- job_name: 'peegeeq-hardware-profiling'
  static_configs:
    - targets: ['peegeeq-test:8080']
  scrape_interval: 5s
  metric_relabel_configs:
    - source_labels: [__name__]
      regex: 'peegeeq_hardware_.*|peegeeq_test_.*|peegeeq_performance_.*'
      action: keep
```

### 3. **Enable Hardware Profiling in Tests**
```java
// In your performance tests
PerformanceMetricsCollector collector = new PerformanceMetricsCollector(
    baseMetrics, 
    "test-instance", 
    true  // Enable hardware profiling
);

collector.startTest("MyPerformanceTest", PerformanceProfile.STANDARD);
// Run test...
HardwareAwarePerformanceResult result = collector.createHardwareAwareResult(
    "MyPerformanceTest", 
    PerformanceProfile.STANDARD,
    "Test configuration",
    testParameters
);

// Register metrics for Prometheus export
collector.registerPerformanceResultMetrics(result);
```

## Alerting Rules

The dashboard integrates with the following alerting rules:

### **High CPU Usage**
- **Condition**: `peegeeq_test_cpu_usage_percent > 95`
- **Duration**: 30 seconds
- **Action**: Warning alert for CPU bottlenecks

### **High Memory Usage**
- **Condition**: `peegeeq_test_memory_usage_percent > 90`
- **Duration**: 30 seconds
- **Action**: Warning alert for memory pressure

### **Resource Constraints**
- **Condition**: `peegeeq_performance_has_resource_constraints == 1`
- **Duration**: Immediate
- **Action**: Critical alert for resource limitations

### **Performance Regression**
- **Condition**: Throughput drops >20% compared to 1 hour ago
- **Duration**: 1 minute
- **Action**: Warning alert for performance degradation

## Best Practices

### **Dashboard Usage**
1. **Start with Hardware Overview**: Verify test environment specifications
2. **Monitor Real-Time Metrics**: Watch resource usage during test execution
3. **Check Constraint Indicators**: Look for resource bottleneck warnings
4. **Compare Across Hardware**: Use hardware profile filters for comparison
5. **Correlate with Traditional Metrics**: Combine with message throughput data

### **Performance Analysis**
1. **Normalize by Hardware**: Use throughput-per-core metrics for fair comparison
2. **Account for Resource Constraints**: Exclude constrained tests from baselines
3. **Track Hardware Evolution**: Monitor performance changes with hardware upgrades
4. **Identify Optimal Configurations**: Find best-performing hardware/software combinations

### **Troubleshooting**
1. **Missing Metrics**: Verify hardware profiling is enabled in tests
2. **No Data**: Check Prometheus scraping configuration
3. **Incorrect Hardware Info**: Validate OSHI library compatibility
4. **Performance Issues**: Reduce monitoring frequency if overhead is high

## Integration with Existing Dashboards

This hardware profiling dashboard complements the existing PeeGeeQ monitoring dashboard:

- **Use Together**: Hardware context enhances traditional performance metrics
- **Cross-Reference**: Link between dashboards using instance labels
- **Unified Alerting**: Combine hardware and application alerts for complete coverage

## Future Enhancements

### **Planned Features**
- **Automated Hardware Recommendations**: ML-based hardware sizing suggestions
- **Performance Prediction Models**: Predict performance on different hardware
- **Cost-Performance Analysis**: Optimize hardware choices for cost efficiency
- **Historical Trend Analysis**: Long-term performance evolution tracking

### **Advanced Analytics**
- **Resource Efficiency Scoring**: Automated efficiency ratings
- **Bottleneck Identification**: AI-powered bottleneck detection
- **Capacity Planning**: Automated scaling recommendations
- **Performance Forecasting**: Predictive performance modeling

## Conclusion

The PeeGeeQ Hardware-Aware Performance Dashboard transforms performance testing from hardware-agnostic measurements to meaningful, reproducible insights. By providing comprehensive hardware context, real-time resource monitoring, and automated constraint detection, this dashboard enables:

✅ **Meaningful Performance Comparisons** across different environments  
✅ **Resource Bottleneck Identification** during test execution  
✅ **Hardware-Aware Performance Optimization** for better resource utilization  
✅ **Automated Performance Regression Detection** accounting for hardware differences  

Performance testing in PeeGeeQ is now truly hardware-aware and production-ready! 🚀
