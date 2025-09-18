-- Performance History Database Schema for H2
-- This schema stores performance test results for historical comparison and trend analysis

-- Performance test runs table - stores metadata about each test execution
CREATE TABLE IF NOT EXISTS performance_test_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL UNIQUE,
    test_name VARCHAR(255) NOT NULL,
    run_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    total_duration_ms BIGINT NOT NULL,
    profiles_tested INT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    environment_info TEXT,
    git_commit VARCHAR(255),
    notes TEXT
);

-- Performance snapshots table - stores individual profile performance data
CREATE TABLE IF NOT EXISTS performance_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    test_name VARCHAR(255) NOT NULL,
    profile_name VARCHAR(100) NOT NULL,
    profile_display_name VARCHAR(255) NOT NULL,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL,
    success BOOLEAN NOT NULL,
    throughput_ops_per_sec DOUBLE PRECISION,
    additional_metrics TEXT, -- JSON string
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES performance_test_runs(run_id) ON DELETE CASCADE
);

-- Performance comparisons table - stores comparison results between profiles
CREATE TABLE IF NOT EXISTS performance_comparisons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    baseline_snapshot_id BIGINT NOT NULL,
    comparison_snapshot_id BIGINT NOT NULL,
    duration_improvement_percent DOUBLE PRECISION NOT NULL,
    throughput_improvement_percent DOUBLE PRECISION NOT NULL,
    is_improvement BOOLEAN NOT NULL,
    is_regression BOOLEAN NOT NULL,
    regression_threshold_percent DOUBLE PRECISION DEFAULT 10.0,
    additional_comparisons TEXT, -- JSON string
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES performance_test_runs(run_id) ON DELETE CASCADE,
    FOREIGN KEY (baseline_snapshot_id) REFERENCES performance_snapshots(id) ON DELETE CASCADE,
    FOREIGN KEY (comparison_snapshot_id) REFERENCES performance_snapshots(id) ON DELETE CASCADE
);

-- Performance trends table - stores aggregated trend data over time
CREATE TABLE IF NOT EXISTS performance_trends (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    test_name VARCHAR(255) NOT NULL,
    profile_name VARCHAR(100) NOT NULL,
    metric_name VARCHAR(100) NOT NULL, -- 'duration_ms', 'throughput_ops_per_sec', etc.
    time_window VARCHAR(50) NOT NULL, -- 'daily', 'weekly', 'monthly'
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    avg_value DOUBLE PRECISION NOT NULL,
    min_value DOUBLE PRECISION NOT NULL,
    max_value DOUBLE PRECISION NOT NULL,
    sample_count INT NOT NULL,
    trend_direction VARCHAR(20), -- 'improving', 'degrading', 'stable'
    trend_percentage DOUBLE PRECISION,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(test_name, profile_name, metric_name, time_window, window_start)
);

-- Note: Views removed for initial implementation to avoid dependency issues

-- Indexes for better query performance (created after tables)
CREATE INDEX IF NOT EXISTS idx_performance_snapshots_run_id ON performance_snapshots(run_id);
CREATE INDEX IF NOT EXISTS idx_performance_snapshots_test_profile ON performance_snapshots(test_name, profile_name);
CREATE INDEX IF NOT EXISTS idx_performance_snapshots_timestamp ON performance_snapshots(start_time);
CREATE INDEX IF NOT EXISTS idx_performance_comparisons_run_id ON performance_comparisons(run_id);
CREATE INDEX IF NOT EXISTS idx_performance_trends_test_profile ON performance_trends(test_name, profile_name);
CREATE INDEX IF NOT EXISTS idx_performance_trends_window ON performance_trends(time_window, window_start);
