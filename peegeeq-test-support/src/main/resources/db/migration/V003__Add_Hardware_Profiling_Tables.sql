-- V003__Add_Hardware_Profiling_Tables.sql
-- Migration to add hardware profiling and resource monitoring tables
-- This enables storage of hardware context and resource usage data for performance tests

-- Hardware profiles table - stores system specifications for performance test context
CREATE TABLE IF NOT EXISTS hardware_profiles (
    id BIGSERIAL PRIMARY KEY,
    profile_hash VARCHAR(64) NOT NULL UNIQUE, -- SHA-256 hash of hardware specifications
    system_description TEXT NOT NULL,
    capture_time TIMESTAMP WITH TIME ZONE NOT NULL,
    hostname VARCHAR(255),
    os_name VARCHAR(100),
    os_version VARCHAR(100),
    os_architecture VARCHAR(50),
    
    -- CPU specifications
    cpu_model TEXT,
    cpu_cores INTEGER,
    cpu_logical_processors INTEGER,
    cpu_max_frequency_hz BIGINT,
    cpu_l1_cache_size BIGINT,
    cpu_l2_cache_size BIGINT,
    cpu_l3_cache_size BIGINT,
    
    -- Memory configuration
    total_memory_bytes BIGINT,
    memory_type VARCHAR(50),
    memory_speed_mhz BIGINT,
    memory_modules INTEGER,
    
    -- Storage characteristics
    storage_type VARCHAR(50),
    total_storage_bytes BIGINT,
    storage_interface VARCHAR(50),
    
    -- Network capabilities
    network_interface VARCHAR(255),
    network_speed_bps BIGINT,
    
    -- JVM configuration
    java_version VARCHAR(100),
    java_vendor VARCHAR(100),
    jvm_name VARCHAR(100),
    jvm_version VARCHAR(100),
    jvm_max_heap_bytes BIGINT,
    jvm_initial_heap_bytes BIGINT,
    gc_algorithm VARCHAR(100),
    
    -- Container environment
    is_containerized BOOLEAN DEFAULT FALSE,
    container_runtime VARCHAR(50),
    container_memory_limit_bytes BIGINT,
    container_cpu_limit DOUBLE PRECISION,
    
    -- Additional metadata
    additional_properties JSONB DEFAULT '{}',
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Resource usage snapshots table - stores real-time resource monitoring data
CREATE TABLE IF NOT EXISTS resource_usage_snapshots (
    id BIGSERIAL PRIMARY KEY,
    test_run_id VARCHAR(255) NOT NULL, -- Links to performance test run
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL,
    sample_count INTEGER NOT NULL,
    
    -- CPU metrics
    peak_cpu_usage_percent DOUBLE PRECISION,
    avg_cpu_usage_percent DOUBLE PRECISION,
    
    -- Memory metrics
    peak_memory_usage_percent DOUBLE PRECISION,
    avg_memory_usage_percent DOUBLE PRECISION,
    peak_jvm_memory_usage_percent DOUBLE PRECISION,
    avg_jvm_memory_usage_percent DOUBLE PRECISION,
    
    -- Disk I/O metrics
    peak_disk_read_rate_bps BIGINT,
    peak_disk_write_rate_bps BIGINT,
    
    -- Network I/O metrics
    peak_network_receive_rate_bps BIGINT,
    peak_network_send_rate_bps BIGINT,
    
    -- System load metrics
    peak_system_load DOUBLE PRECISION,
    avg_system_load DOUBLE PRECISION,
    
    -- Thread metrics
    peak_thread_count INTEGER,
    
    -- Resource constraint flags
    is_high_resource_usage BOOLEAN DEFAULT FALSE,
    has_resource_constraints BOOLEAN DEFAULT FALSE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Resource samples table - stores individual resource monitoring samples
CREATE TABLE IF NOT EXISTS resource_samples (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Resource metrics at this point in time
    cpu_usage_percent DOUBLE PRECISION,
    memory_usage_percent DOUBLE PRECISION,
    used_memory_bytes BIGINT,
    total_memory_bytes BIGINT,
    jvm_memory_usage_percent DOUBLE PRECISION,
    jvm_used_memory_bytes BIGINT,
    jvm_max_memory_bytes BIGINT,
    
    disk_read_rate_bps BIGINT,
    disk_write_rate_bps BIGINT,
    network_receive_rate_bps BIGINT,
    network_send_rate_bps BIGINT,
    
    system_load DOUBLE PRECISION,
    thread_count INTEGER,
    
    FOREIGN KEY (snapshot_id) REFERENCES resource_usage_snapshots(id) ON DELETE CASCADE
);

-- Hardware-aware performance results table - extends performance snapshots with hardware context
CREATE TABLE IF NOT EXISTS hardware_aware_performance_results (
    id BIGSERIAL PRIMARY KEY,
    test_run_id VARCHAR(255) NOT NULL,
    test_name VARCHAR(255) NOT NULL,
    test_configuration TEXT,
    test_start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    test_end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    test_duration_ms BIGINT NOT NULL,
    
    -- Links to hardware context
    hardware_profile_id BIGINT,
    resource_snapshot_id BIGINT,
    
    -- Performance metrics (stored as JSONB for flexibility)
    performance_metrics JSONB NOT NULL DEFAULT '{}',
    test_parameters JSONB DEFAULT '{}',
    
    -- Analysis flags
    has_resource_constraints BOOLEAN DEFAULT FALSE,
    has_high_resource_usage BOOLEAN DEFAULT FALSE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    FOREIGN KEY (hardware_profile_id) REFERENCES hardware_profiles(id),
    FOREIGN KEY (resource_snapshot_id) REFERENCES resource_usage_snapshots(id)
);

-- Performance regression analysis table - stores cross-hardware performance comparisons
CREATE TABLE IF NOT EXISTS performance_regressions (
    id BIGSERIAL PRIMARY KEY,
    baseline_result_id BIGINT NOT NULL,
    comparison_result_id BIGINT NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    baseline_value DOUBLE PRECISION NOT NULL,
    comparison_value DOUBLE PRECISION NOT NULL,
    percent_change DOUBLE PRECISION NOT NULL,
    is_same_hardware BOOLEAN NOT NULL,
    is_regression BOOLEAN NOT NULL,
    is_improvement BOOLEAN NOT NULL,
    regression_threshold DOUBLE PRECISION DEFAULT 10.0,
    
    analysis_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    FOREIGN KEY (baseline_result_id) REFERENCES hardware_aware_performance_results(id),
    FOREIGN KEY (comparison_result_id) REFERENCES hardware_aware_performance_results(id)
);

-- Create indexes for efficient querying

-- Hardware profiles indexes
CREATE INDEX IF NOT EXISTS idx_hardware_profiles_hash ON hardware_profiles(profile_hash);
CREATE INDEX IF NOT EXISTS idx_hardware_profiles_system ON hardware_profiles(os_name, cpu_model, total_memory_bytes);
CREATE INDEX IF NOT EXISTS idx_hardware_profiles_capture_time ON hardware_profiles(capture_time);

-- Resource usage snapshots indexes
CREATE INDEX IF NOT EXISTS idx_resource_snapshots_test_run ON resource_usage_snapshots(test_run_id);
CREATE INDEX IF NOT EXISTS idx_resource_snapshots_time_range ON resource_usage_snapshots(start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_resource_snapshots_constraints ON resource_usage_snapshots(has_resource_constraints, is_high_resource_usage);

-- Resource samples indexes
CREATE INDEX IF NOT EXISTS idx_resource_samples_snapshot ON resource_samples(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_resource_samples_timestamp ON resource_samples(timestamp);

-- Hardware-aware performance results indexes
CREATE INDEX IF NOT EXISTS idx_hardware_results_test_run ON hardware_aware_performance_results(test_run_id);
CREATE INDEX IF NOT EXISTS idx_hardware_results_test_name ON hardware_aware_performance_results(test_name);
CREATE INDEX IF NOT EXISTS idx_hardware_results_hardware_profile ON hardware_aware_performance_results(hardware_profile_id);
CREATE INDEX IF NOT EXISTS idx_hardware_results_time_range ON hardware_aware_performance_results(test_start_time, test_end_time);
CREATE INDEX IF NOT EXISTS idx_hardware_results_constraints ON hardware_aware_performance_results(has_resource_constraints, has_high_resource_usage);

-- Performance regressions indexes
CREATE INDEX IF NOT EXISTS idx_regressions_baseline ON performance_regressions(baseline_result_id);
CREATE INDEX IF NOT EXISTS idx_regressions_comparison ON performance_regressions(comparison_result_id);
CREATE INDEX IF NOT EXISTS idx_regressions_metric ON performance_regressions(metric_name);
CREATE INDEX IF NOT EXISTS idx_regressions_analysis ON performance_regressions(is_regression, is_improvement, is_same_hardware);

-- Add comments explaining the hardware profiling schema

COMMENT ON TABLE hardware_profiles IS 
'Stores comprehensive hardware specifications for performance test context. 
Each unique hardware configuration gets a single record identified by profile_hash.';

COMMENT ON COLUMN hardware_profiles.profile_hash IS 
'SHA-256 hash of hardware specifications for deduplication and fast lookups';

COMMENT ON TABLE resource_usage_snapshots IS 
'Stores aggregated resource usage statistics collected during performance test execution.
Links to performance test runs and provides resource constraint analysis.';

COMMENT ON TABLE resource_samples IS 
'Stores individual resource monitoring samples collected at regular intervals.
Provides detailed time-series data for resource usage analysis.';

COMMENT ON TABLE hardware_aware_performance_results IS 
'Enhanced performance test results that include hardware context and resource usage.
Enables meaningful cross-environment performance comparison and analysis.';

COMMENT ON TABLE performance_regressions IS 
'Stores performance regression analysis results accounting for hardware differences.
Enables automated detection of performance degradations and improvements.';

-- Create a view for easy hardware-aware performance analysis
CREATE OR REPLACE VIEW performance_analysis_view AS
SELECT 
    hpr.id,
    hpr.test_name,
    hpr.test_configuration,
    hpr.test_duration_ms,
    hpr.performance_metrics,
    hpr.has_resource_constraints,
    hpr.has_high_resource_usage,
    
    -- Hardware context
    hp.system_description,
    hp.cpu_model,
    hp.cpu_cores,
    hp.total_memory_bytes / (1024*1024*1024) as total_memory_gb,
    hp.storage_type,
    hp.is_containerized,
    
    -- Resource usage summary
    rus.peak_cpu_usage_percent,
    rus.avg_cpu_usage_percent,
    rus.peak_memory_usage_percent,
    rus.avg_memory_usage_percent,
    rus.peak_jvm_memory_usage_percent,
    rus.avg_jvm_memory_usage_percent,
    
    hpr.test_start_time,
    hpr.created_at
    
FROM hardware_aware_performance_results hpr
LEFT JOIN hardware_profiles hp ON hpr.hardware_profile_id = hp.id
LEFT JOIN resource_usage_snapshots rus ON hpr.resource_snapshot_id = rus.id
ORDER BY hpr.test_start_time DESC;

COMMENT ON VIEW performance_analysis_view IS 
'Comprehensive view combining performance results with hardware context and resource usage.
Provides easy access to all relevant data for performance analysis and reporting.';
