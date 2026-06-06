# PostgreSQL Notice Handling Guide

**Author**: Mark A Ray-Smith, Cityline Ltd
**Date**: December 2025
**Version**: 1.0

---

This guide explains how to use PostgreSQL notice handling in the PeeGeeQ system with the Vert.x 5.x Reactive PostgreSQL client. It covers how to raise informational messages from PostgreSQL, configure notice handling behavior, and monitor notice patterns in production.

## Overview

PeeGeeQ provides a structured approach to handling PostgreSQL notices that:
- Separates actual PostgreSQL warnings from PeeGeeQ-specific informational messages
- Logs diagnostic information at appropriate levels with structured fields for production monitoring
- Provides configurable log levels for debugging and operational flexibility
- Captures metrics for different notice types to enable production observability
- Supports integration with log aggregation systems (ELK, Splunk, etc.)

## How It Works

PeeGeeQ uses PostgreSQL's `RAISE INFO` level combined with custom PeeGeeQ info codes (from `PeeGeeQInfoCodes`) for precise filtering, structured logging, and metrics collection.

### Raising Informational Messages from PostgreSQL

When you need to send informational messages from PostgreSQL stored procedures or triggers, use `RAISE INFO` with a PeeGeeQ info code in the `DETAIL` field:

```sql
-- CORRECT: Code in DETAIL field for structured parsing
RAISE INFO 'Schema setup complete' USING DETAIL = 'PGQINF0550';

-- CORRECT: Multiple details can be combined
RAISE INFO 'Outbox message enqueued' USING DETAIL = 'PGQINF0100|queue=orders|msg_id=12345';

-- INCORRECT: Code in message body (harder to parse)
RAISE INFO 'PGQINF0550: Schema setup complete';
```

**Code Format**: `PGQINF` followed by exactly 4 digits (e.g., `PGQINF0100`, `PGQINF0550`). See `PeeGeeQInfoCodes` for the complete catalog.

**Why `RAISE INFO`?**
In PostgreSQL, `INFO` level messages are *always* sent to the client, regardless of the `client_min_messages` setting. This makes them the most reliable carrier for system status updates.

### How PeeGeeQ Processes Notices

The `PgConnectionManager` automatically attaches a `noticeHandler` to every `PgConnection`. This handler:
- Filters notices based on PeeGeeQ info codes using regex pattern matching
- Logs messages with structured fields for easy parsing
- Collects metrics for observability
- Respects configurable log levels

```java
private static final Pattern PEEGEEQ_INFO_CODE_PATTERN = Pattern.compile("PGQINF\\d{4}");

connection.noticeHandler(notice -> {
    String msg = notice.getMessage();
    String detail = notice.getDetail();
    String severity = notice.getSeverity();
    String sqlState = notice.getCode();

    // Extract PeeGeeQ info code from DETAIL field (primary location)
    String infoCode = extractInfoCode(detail);

    if (infoCode != null) {
        // PeeGeeQ informational message - structured logging
        noticeMetrics.incrementInfoNotices(infoCode);

        if (config.isPeeGeeQInfoLoggingEnabled()) {
            // Structured log entry for production monitoring
            logger.atLevel(config.getPeeGeeQInfoLogLevel())
                  .addKeyValue("notice_type", "peegeeq_info")
                  .addKeyValue("info_code", infoCode)
                  .addKeyValue("message", msg)
                  .addKeyValue("detail", detail)
                  .addKeyValue("sql_state", sqlState)
                  .log("PeeGeeQ Info: {}", msg);
        }
    } else if ("WARNING".equalsIgnoreCase(severity)) {
        // Actual PostgreSQL warning - always log
        noticeMetrics.incrementWarnings(sqlState);

        logger.atWarn()
              .addKeyValue("notice_type", "postgres_warning")
              .addKeyValue("sql_state", sqlState)
              .addKeyValue("message", msg)
              .addKeyValue("detail", detail)
              .log("PostgreSQL Warning: {}", msg);
    } else {
        // Other notices (DEBUG, LOG, NOTICE) - configurable
        noticeMetrics.incrementOtherNotices(severity);

        if (config.isOtherNoticesLoggingEnabled()) {
            logger.atLevel(config.getOtherNoticesLogLevel())
                  .addKeyValue("notice_type", "postgres_notice")
                  .addKeyValue("severity", severity)
                  .addKeyValue("sql_state", sqlState)
                  .addKeyValue("message", msg)
                  .log("PostgreSQL Notice: {}", msg);
        }
    }
});

private String extractInfoCode(String detail) {
    if (detail == null) return null;

    // Extract first occurrence of PGQINFxxxx pattern
    Matcher matcher = PEEGEEQ_INFO_CODE_PATTERN.matcher(detail);
    return matcher.find() ? matcher.group() : null;
}
```

## Configuration

You can control notice handling behavior through configuration properties. The system provides three categories of notices:

1. **PeeGeeQ Info Messages** - Messages with PeeGeeQ info codes (e.g., `PGQINF0100`)
2. **PostgreSQL Warnings** - Actual warnings from PostgreSQL (always logged at WARN level)
3. **Other PostgreSQL Notices** - Standard PostgreSQL notices (DEBUG, LOG, NOTICE levels)

### Configuration Properties

```properties
# PeeGeeQ Info Messages
peegeeq.notices.info.enabled=true          # Enable/disable logging of PeeGeeQ info messages
peegeeq.notices.info.level=INFO            # Log level: INFO, DEBUG, or TRACE

# Other PostgreSQL Notices
peegeeq.notices.other.enabled=false        # Enable/disable logging of other notices
peegeeq.notices.other.level=DEBUG          # Log level: DEBUG or TRACE

# Metrics (always enabled)
peegeeq.notices.metrics.enabled=true       # Enable/disable metrics collection
```

### Configuration Examples

**Production Environment** - Info messages at INFO level, suppress other notices:
```properties
peegeeq.notices.info.enabled=true
peegeeq.notices.info.level=INFO
peegeeq.notices.other.enabled=false
```

**Development Environment** - All notices visible for debugging:
```properties
peegeeq.notices.info.enabled=true
peegeeq.notices.info.level=DEBUG
peegeeq.notices.other.enabled=true
peegeeq.notices.other.level=DEBUG
```

**Minimal Configuration** - Only warnings and metrics:
```properties
peegeeq.notices.info.enabled=false
peegeeq.notices.other.enabled=false
```

## Monitoring and Metrics

PeeGeeQ collects metrics for all notice types, providing observability into notice patterns:

### Available Metrics

| Metric Name | Type | Labels | Description |
|------------|------|--------|-------------|
| `peegeeq.notices.info.total` | Counter | `code` | Count of PeeGeeQ info notices by code (e.g., `PGQINF0100`) |
| `peegeeq.notices.warnings.total` | Counter | `sql_state` | Count of PostgreSQL warnings by SQL state |
| `peegeeq.notices.other.total` | Counter | `severity` | Count of other notices by severity level |
| `peegeeq.notices.handler.duration` | Histogram | - | Notice handler execution time in seconds |

### Using Metrics in Production

**Monitor operational patterns:**
```promql
# Track frequency of specific info codes
rate(peegeeq_notices_info_total{code="PGQINF0100"}[5m])

# Alert on unexpected warnings
increase(peegeeq_notices_warnings_total[1h]) > 10
```

**Performance monitoring:**
```promql
# Track notice handler performance
histogram_quantile(0.95, peegeeq_notices_handler_duration_seconds)
```

**Common use cases:**
- Monitor frequency of specific info codes to detect operational patterns
- Alert on unexpected warning SQL states
- Track notice handler performance impact
- Correlate notice patterns with application behavior

## Quick Start Guide

### Step 1: Add Info Codes to Your SQL

When writing PostgreSQL functions or triggers, use `RAISE INFO` with a PeeGeeQ info code:

```sql
CREATE OR REPLACE FUNCTION my_function()
RETURNS void AS $$
BEGIN
    -- Your logic here

    -- Raise informational message
    RAISE INFO 'Operation completed successfully'
        USING DETAIL = 'PGQINF0100';
END;
$$ LANGUAGE plpgsql;
```

### Step 2: Choose or Create an Info Code

Check `PeeGeeQInfoCodes.java` for existing codes, or add a new one:

```java
// In PeeGeeQInfoCodes.java
public static final String MY_OPERATION_COMPLETE = "PGQINF0700";
```

Info codes are organized by category:
- `0100-0199`: Outbox operations
- `0200-0299`: Event store operations
- `0300-0399`: Consumer group operations
- `0550-0599`: Schema operations
- `0600-0649`: Notice handler operations
- `0650-0699`: Cleanup/maintenance operations

### Step 3: Configure Logging (Optional)

By default, PeeGeeQ info messages are logged at INFO level. Adjust if needed:

```properties
# In your application.properties or peegeeq.properties
peegeeq.notices.info.level=DEBUG  # For more verbose logging
```

### Step 4: Monitor in Production

Use the metrics to track notice patterns:

```promql
# Dashboard query - notices by code
sum by (code) (rate(peegeeq_notices_info_total[5m]))
```

## Best Practices

### When to Use PeeGeeQ Info Codes

✅ **DO use info codes for:**
- Successful completion of operations (e.g., "Schema setup complete")
- Operational status updates (e.g., "Message enqueued")
- Cleanup/maintenance results (e.g., "Cleaned up 100 records")
- Diagnostic information useful for troubleshooting

❌ **DON'T use info codes for:**
- Error conditions (use exceptions instead)
- Debug-only information (use PostgreSQL's `RAISE DEBUG`)
- Temporary development messages

### Structured Detail Fields

You can include additional structured data in the DETAIL field:

```sql
-- Include multiple pieces of information
RAISE INFO 'Message enqueued'
    USING DETAIL = 'PGQINF0100|queue=orders|msg_id=12345|priority=high';
```

The notice handler will extract the first info code (`PGQINF0100`) and log all details in structured fields.

### Log Level Guidelines

- **INFO**: Normal operational messages (default for PeeGeeQ info codes)
- **DEBUG**: Detailed diagnostic information for troubleshooting
- **TRACE**: Very verbose information for deep debugging

## Benefits

**Operational Benefits:**
- ✅ No log false alarms - informational events don't trigger warning-level alerts
- ✅ Production monitoring - structured logs integrate with log aggregation systems (ELK, Splunk, etc.)
- ✅ Configurable verbosity - adjust logging levels without code changes

**Observability Benefits:**
- ✅ Metrics-driven monitoring - track notice patterns and frequencies
- ✅ Traceability - standard `PGQINF` codes enable correlation across stack layers
- ✅ Performance visibility - measure notice handler overhead

**Maintainability Benefits:**
- ✅ Structured logging - parseable fields enable automated analysis
- ✅ Selective suppression - verbose PostgreSQL notices can be filtered without losing critical visibility
- ✅ Consistent patterns - regex-based code extraction prevents parsing ambiguity

## Troubleshooting

### Issue: Info messages not appearing in logs

**Check:**
1. Verify `peegeeq.notices.info.enabled=true` in your configuration
2. Check that your log level allows INFO messages (e.g., not set to WARN or ERROR only)
3. Confirm the SQL uses `RAISE INFO` (not `RAISE NOTICE` or `RAISE DEBUG`)
4. Verify the info code is in the DETAIL field: `USING DETAIL = 'PGQINF0100'`

### Issue: Too many notices in logs

**Solution:**
Adjust the log level or disable certain notice categories:

```properties
# Reduce verbosity
peegeeq.notices.info.level=DEBUG  # Won't show unless logger is at DEBUG
peegeeq.notices.other.enabled=false  # Suppress other PostgreSQL notices
```

### Issue: Metrics not being collected

**Check:**
1. Verify `peegeeq.notices.metrics.enabled=true`
2. Ensure a `MeterRegistry` is configured in your application
3. Check that your metrics backend is properly configured

### Issue: Invalid info code format

**Error:** Info code not recognized (e.g., `PGQINF99` or `PGQINFO0100`)

**Solution:**
Info codes must follow the exact format: `PGQINF` + exactly 4 digits
- ✅ Correct: `PGQINF0100`, `PGQINF0550`
- ❌ Incorrect: `PGQINF99`, `PGQINFO0100`, `PGQINF01`

## Advanced Topics

### Custom Notice Handler Implementation

If you need custom notice handling logic, you can implement the `NoticeMetrics` interface:

```java
public class CustomNoticeMetrics implements NoticeMetrics {
    @Override
    public void incrementInfoNotices(String infoCode) {
        // Your custom logic
    }

    // ... implement other methods
}
```

Then configure it in your `PeeGeeQManager` initialization.

### Integrating with Log Aggregation Systems

The structured logging format makes it easy to parse and analyze logs in systems like ELK or Splunk:

**Elasticsearch query example:**
```json
{
  "query": {
    "bool": {
      "must": [
        { "match": { "notice_type": "peegeeq_info" }},
        { "match": { "info_code": "PGQINF0100" }}
      ]
    }
  }
}
```

**Splunk query example:**
```
index=app notice_type="peegeeq_info" info_code="PGQINF0100"
| stats count by message
```

### Structured Log Fields

Each notice is logged with the following structured fields:

| Field | Description | Example |
|-------|-------------|---------|
| `notice_type` | Type of notice | `peegeeq_info`, `postgres_warning`, `postgres_notice` |
| `info_code` | PeeGeeQ info code (if applicable) | `PGQINF0100` |
| `message` | The notice message | `Schema setup complete` |
| `detail` | Additional details | `PGQINF0550` or `PGQINF0100\|queue=orders` |
| `sql_state` | PostgreSQL SQL state code | `00000` |
| `severity` | PostgreSQL severity level | `INFO`, `WARNING`, `NOTICE` |

## Migration Guide

If you have existing SQL code using `RAISE NOTICE`, follow these steps to migrate to the PeeGeeQ notice handling system:

### Step 1: Identify Existing RAISE Statements

Search your SQL files for `RAISE NOTICE` or `RAISE WARNING`:

```bash
grep -r "RAISE NOTICE" src/main/resources/db/
grep -r "RAISE WARNING" src/main/resources/db/
```

### Step 2: Convert to RAISE INFO with Info Codes

For each `RAISE NOTICE` statement:

**Before:**
```sql
RAISE NOTICE 'Cleaned up % records', deleted_count;
```

**After:**
```sql
RAISE INFO 'Cleaned up % records'
    USING DETAIL = 'PGQINF0650';
```

### Step 3: Add Info Codes to PeeGeeQInfoCodes

If you need new info codes, add them to `PeeGeeQInfoCodes.java`:

```java
// Cleanup/Maintenance Info (0650-0699)
public static final String CLEANUP_COMPLETED = "PGQINF0650";
public static final String CLEANUP_MESSAGE_PROCESSING = "PGQINF0651";
public static final String CLEANUP_OUTBOX_MESSAGES = "PGQINF0652";
```

Choose a code range that matches your operation category:
- `0100-0199`: Outbox operations
- `0200-0299`: Event store operations
- `0300-0399`: Consumer group operations
- `0550-0599`: Schema operations
- `0650-0699`: Cleanup/maintenance operations

### Step 4: Test Your Changes

Run your SQL and verify the notices appear correctly:

1. Check logs for structured fields
2. Verify metrics are being collected
3. Confirm the log level is appropriate

## Examples

### Example 1: Outbox Message Enqueued

**SQL Function:**
```sql
CREATE OR REPLACE FUNCTION enqueue_outbox_message(
    p_queue_name TEXT,
    p_message_id UUID
)
RETURNS void AS $$
BEGIN
    -- Insert message logic here

    RAISE INFO 'Outbox message enqueued'
        USING DETAIL = 'PGQINF0100|queue=' || p_queue_name || '|msg_id=' || p_message_id;
END;
$$ LANGUAGE plpgsql;
```

**Resulting Log:**
```json
{
  "level": "INFO",
  "message": "PeeGeeQ Info: Outbox message enqueued",
  "notice_type": "peegeeq_info",
  "info_code": "PGQINF0100",
  "detail": "PGQINF0100|queue=orders|msg_id=12345-67890",
  "sql_state": "00000"
}
```

### Example 2: Schema Setup Complete

**SQL Function:**
```sql
CREATE OR REPLACE FUNCTION setup_schema()
RETURNS void AS $$
BEGIN
    -- Schema setup logic

    RAISE INFO 'Schema setup complete'
        USING DETAIL = 'PGQINF0550';
END;
$$ LANGUAGE plpgsql;
```

**Resulting Log:**
```json
{
  "level": "INFO",
  "message": "PeeGeeQ Info: Schema setup complete",
  "notice_type": "peegeeq_info",
  "info_code": "PGQINF0550",
  "detail": "PGQINF0550",
  "sql_state": "00000"
}
```

### Example 3: Cleanup Operation

**SQL Function:**
```sql
CREATE OR REPLACE FUNCTION cleanup_old_messages()
RETURNS void AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM outbox_messages
    WHERE created_at < NOW() - INTERVAL '30 days';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    RAISE INFO 'Cleaned up % old messages'
        USING DETAIL = 'PGQINF0651|count=' || deleted_count;
END;
$$ LANGUAGE plpgsql;
```

**Resulting Log:**
```json
{
  "level": "INFO",
  "message": "PeeGeeQ Info: Cleaned up 150 old messages",
  "notice_type": "peegeeq_info",
  "info_code": "PGQINF0651",
  "detail": "PGQINF0651|count=150",
  "sql_state": "00000"
}
```

## Reference

### Complete Notice Handler Implementation

For reference, here's how the notice handler processes different types of notices:

```java
private static final Pattern PEEGEEQ_INFO_CODE_PATTERN = Pattern.compile("PGQINF\\d{4}");

connection.noticeHandler(notice -> {
    String msg = notice.getMessage();
    String detail = notice.getDetail();
    String severity = notice.getSeverity();
    String sqlState = notice.getCode();

    // Extract PeeGeeQ info code from DETAIL field
    String infoCode = extractInfoCode(detail);

    if (infoCode != null) {
        // PeeGeeQ informational message - structured logging
        noticeMetrics.incrementInfoNotices(infoCode);

        if (config.isPeeGeeQInfoLoggingEnabled()) {
            logger.atLevel(config.getPeeGeeQInfoLogLevel())
                  .addKeyValue("notice_type", "peegeeq_info")
                  .addKeyValue("info_code", infoCode)
                  .addKeyValue("message", msg)
                  .addKeyValue("detail", detail)
                  .addKeyValue("sql_state", sqlState)
                  .log("PeeGeeQ Info: {}", msg);
        }
    } else if ("WARNING".equalsIgnoreCase(severity)) {
        // Actual PostgreSQL warning - always log
        noticeMetrics.incrementWarnings(sqlState);

        logger.atWarn()
              .addKeyValue("notice_type", "postgres_warning")
              .addKeyValue("sql_state", sqlState)
              .addKeyValue("message", msg)
              .addKeyValue("detail", detail)
              .log("PostgreSQL Warning: {}", msg);
    } else {
        // Other notices (DEBUG, LOG, NOTICE) - configurable
        noticeMetrics.incrementOtherNotices(severity);

        if (config.isOtherNoticesLoggingEnabled()) {
            logger.atLevel(config.getOtherNoticesLogLevel())
                  .addKeyValue("notice_type", "postgres_notice")
                  .addKeyValue("severity", severity)
                  .addKeyValue("sql_state", sqlState)
                  .addKeyValue("message", msg)
                  .log("PostgreSQL Notice: {}", msg);
        }
    }
});

private String extractInfoCode(String detail) {
    if (detail == null) return null;
    Matcher matcher = PEEGEEQ_INFO_CODE_PATTERN.matcher(detail);
    return matcher.find() ? matcher.group() : null;
}
```

### PeeGeeQ Info Code Categories

| Code Range | Category | Examples |
|------------|----------|----------|
| 0100-0199 | Outbox operations | Message enqueued, message processed |
| 0200-0299 | Event store operations | Event stored, event retrieved |
| 0300-0399 | Consumer group operations | Consumer registered, offset committed |
| 0550-0599 | Schema operations | Schema created, schema updated |
| 0600-0649 | Notice handler operations | Handler attached, notice received |
| 0650-0699 | Cleanup/maintenance | Records cleaned, maintenance completed |

### Configuration Reference

Complete list of configuration properties:

```properties
# PeeGeeQ Info Messages
peegeeq.notices.info.enabled=true          # Default: true
peegeeq.notices.info.level=INFO            # Default: INFO (options: INFO, DEBUG, TRACE)

# Other PostgreSQL Notices
peegeeq.notices.other.enabled=false        # Default: false
peegeeq.notices.other.level=DEBUG          # Default: DEBUG (options: DEBUG, TRACE)

# Metrics
peegeeq.notices.metrics.enabled=true       # Default: true
```

## FAQ

**Q: What's the difference between RAISE INFO and RAISE NOTICE?**

A: `RAISE INFO` messages are always sent to the client regardless of `client_min_messages` setting, making them more reliable for system status updates. `RAISE NOTICE` messages may be suppressed depending on PostgreSQL configuration.

**Q: Can I use multiple info codes in one DETAIL field?**

A: Yes, but only the first code will be extracted. Example: `DETAIL = 'PGQINF0100|PGQINF0200'` will extract `PGQINF0100`.

**Q: Do I need to restart the application to change log levels?**

A: It depends on your configuration system. If using externalized configuration with refresh support, log levels can be changed dynamically. Otherwise, a restart is required.

**Q: Will notice handling impact performance?**

A: The performance impact is minimal. The notice handler uses efficient regex matching and structured logging. You can monitor the `peegeeq.notices.handler.duration` metric to track handler execution time.

**Q: Can I disable notice handling entirely?**

A: Yes, set both `peegeeq.notices.info.enabled=false` and `peegeeq.notices.other.enabled=false`. Metrics will still be collected unless you also set `peegeeq.notices.metrics.enabled=false`.

**Q: What happens if I forget to add an info code to the DETAIL field?**

A: The notice will be treated as a standard PostgreSQL notice and logged according to the `peegeeq.notices.other.*` configuration (disabled by default in production).

## Related Documentation

- **PeeGeeQ Configuration Guide**: Complete configuration reference
- **PeeGeeQ Architecture Guide**: System architecture and design patterns
- **PeeGeeQInfoCodes Reference**: Complete list of info codes and their meanings
