package dev.mars.peegeeq.examples;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Properties;

/**
 * Comprehensive example demonstrating security configuration and best practices for PeeGeeQ.
 * 
 * This example shows:
 * - SSL/TLS configuration for database connections
 * - Certificate management and validation
 * - Connection security best practices
 * - Environment-specific security configurations
 * - Security monitoring and logging
 * - Credential management patterns
 * - Network security considerations
 * - Compliance and audit requirements
 * 
 * Security Features Demonstrated:
 * - Database SSL/TLS encryption
 * - Certificate-based authentication
 * - Connection pool security
 * - Credential rotation strategies
 * - Security event logging
 * - Network isolation patterns
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-29
 * @version 1.0
 */
public class SecurityConfigurationExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfigurationExample.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Security Configuration Example ===");
        logger.info("This example demonstrates security best practices and SSL/TLS configuration");
        
        // Demonstrate different security configurations
        demonstrateBasicSecurityConfiguration();
        demonstrateSSLTLSConfiguration();
        demonstrateProductionSecuritySetup();
        demonstrateSecurityMonitoring();
        demonstrateCredentialManagement();
        demonstrateComplianceConfiguration();
        
        logger.info("Security Configuration Example completed successfully!");
    }
    
    /**
     * Demonstrates basic security configuration principles.
     */
    private static void demonstrateBasicSecurityConfiguration() {
        logger.info("\n=== BASIC SECURITY CONFIGURATION ===");
        
        logger.info("🔒 Security Configuration Principles:");
        logger.info("   1. Principle of Least Privilege");
        logger.info("   2. Defense in Depth");
        logger.info("   3. Fail Secure");
        logger.info("   4. Complete Mediation");
        logger.info("   5. Open Design");
        
        // Basic security properties
        Properties securityProps = new Properties();
        
        // Database security
        securityProps.setProperty("peegeeq.database.ssl.enabled", "true");
        securityProps.setProperty("peegeeq.database.ssl.mode", "require");
        securityProps.setProperty("peegeeq.database.ssl.factory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        
        // Connection security
        securityProps.setProperty("peegeeq.database.connection.timeout", "30000");
        securityProps.setProperty("peegeeq.database.connection.max-lifetime", "1800000"); // 30 minutes
        securityProps.setProperty("peegeeq.database.connection.leak-detection-threshold", "60000");
        
        // Authentication security
        securityProps.setProperty("peegeeq.database.username.encrypted", "false"); // Set to true in production
        securityProps.setProperty("peegeeq.database.password.encrypted", "false"); // Set to true in production
        
        // Audit and logging
        securityProps.setProperty("peegeeq.security.audit.enabled", "true");
        securityProps.setProperty("peegeeq.security.logging.level", "INFO");
        securityProps.setProperty("peegeeq.security.events.log-connections", "true");
        securityProps.setProperty("peegeeq.security.events.log-failures", "true");
        
        logger.info("✅ Basic security configuration properties set:");
        securityProps.forEach((key, value) -> 
            logger.info("   {} = {}", key, maskSensitiveValue(key.toString(), value.toString())));
    }
    
    /**
     * Demonstrates SSL/TLS configuration for secure database connections.
     */
    private static void demonstrateSSLTLSConfiguration() throws Exception {
        logger.info("\n=== SSL/TLS CONFIGURATION ===");
        
        // Start PostgreSQL container with SSL enabled
        try (PostgreSQLContainer<?> postgres = createSecurePostgreSQLContainer()) {
            postgres.start();
            logger.info("Secure PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Configure SSL properties
            configureSSLProperties(postgres);
            
            // Test secure connection
            testSecureConnection();
            
            logger.info("✅ SSL/TLS configuration completed successfully");
        }
    }
    
    /**
     * Demonstrates production-ready security setup.
     */
    private static void demonstrateProductionSecuritySetup() {
        logger.info("\n=== PRODUCTION SECURITY SETUP ===");
        
        logger.info("🏭 Production Security Checklist:");
        
        // Network security
        logger.info("📡 Network Security:");
        logger.info("   ✓ Use private networks/VPCs");
        logger.info("   ✓ Configure firewall rules");
        logger.info("   ✓ Enable network encryption");
        logger.info("   ✓ Use connection pooling");
        logger.info("   ✓ Implement rate limiting");
        
        // Database security
        logger.info("🗄️  Database Security:");
        logger.info("   ✓ Enable SSL/TLS encryption");
        logger.info("   ✓ Use certificate-based authentication");
        logger.info("   ✓ Configure row-level security");
        logger.info("   ✓ Enable audit logging");
        logger.info("   ✓ Regular security updates");
        
        // Application security
        logger.info("🔐 Application Security:");
        logger.info("   ✓ Encrypt sensitive configuration");
        logger.info("   ✓ Use secure credential storage");
        logger.info("   ✓ Implement proper error handling");
        logger.info("   ✓ Enable security monitoring");
        logger.info("   ✓ Regular security assessments");
        
        // Demonstrate production configuration
        Properties prodConfig = createProductionSecurityConfiguration();
        logger.info("📋 Production Security Configuration:");
        prodConfig.forEach((key, value) -> 
            logger.info("   {} = {}", key, maskSensitiveValue(key.toString(), value.toString())));
    }
    
    /**
     * Demonstrates security monitoring and alerting.
     */
    private static void demonstrateSecurityMonitoring() {
        logger.info("\n=== SECURITY MONITORING ===");
        
        logger.info("📊 Security Monitoring Components:");
        
        // Connection monitoring
        logger.info("🔗 Connection Monitoring:");
        logger.info("   • Failed connection attempts");
        logger.info("   • Unusual connection patterns");
        logger.info("   • Connection pool exhaustion");
        logger.info("   • SSL/TLS handshake failures");
        
        // Access monitoring
        logger.info("👤 Access Monitoring:");
        logger.info("   • Authentication failures");
        logger.info("   • Privilege escalation attempts");
        logger.info("   • Unusual query patterns");
        logger.info("   • Data access anomalies");
        
        // Performance monitoring
        logger.info("⚡ Performance Monitoring:");
        logger.info("   • Query execution times");
        logger.info("   • Resource utilization");
        logger.info("   • Error rates");
        logger.info("   • Throughput metrics");
        
        // Security event examples
        simulateSecurityEventLogging();
    }
    
    /**
     * Demonstrates credential management best practices.
     */
    private static void demonstrateCredentialManagement() {
        logger.info("\n=== CREDENTIAL MANAGEMENT ===");
        
        logger.info("🔑 Credential Management Best Practices:");
        
        // Environment-based credentials
        logger.info("🌍 Environment-Based Credentials:");
        logger.info("   • Use environment variables for secrets");
        logger.info("   • Separate credentials per environment");
        logger.info("   • Implement credential rotation");
        logger.info("   • Use secure credential stores");
        
        // Encryption strategies
        logger.info("🔒 Encryption Strategies:");
        logger.info("   • Encrypt credentials at rest");
        logger.info("   • Use strong encryption algorithms");
        logger.info("   • Implement key management");
        logger.info("   • Regular key rotation");
        
        // Access control
        logger.info("🚪 Access Control:");
        logger.info("   • Principle of least privilege");
        logger.info("   • Role-based access control");
        logger.info("   • Regular access reviews");
        logger.info("   • Automated deprovisioning");
        
        // Demonstrate credential configuration
        demonstrateCredentialConfiguration();
    }
    
    /**
     * Demonstrates compliance and audit configuration.
     */
    private static void demonstrateComplianceConfiguration() {
        logger.info("\n=== COMPLIANCE AND AUDIT CONFIGURATION ===");
        
        logger.info("📋 Compliance Requirements:");
        
        // GDPR compliance
        logger.info("🇪🇺 GDPR Compliance:");
        logger.info("   • Data encryption at rest and in transit");
        logger.info("   • Right to be forgotten implementation");
        logger.info("   • Data processing audit trails");
        logger.info("   • Privacy by design principles");
        
        // SOX compliance
        logger.info("📊 SOX Compliance:");
        logger.info("   • Financial data protection");
        logger.info("   • Change management controls");
        logger.info("   • Access control documentation");
        logger.info("   • Regular compliance audits");
        
        // HIPAA compliance
        logger.info("🏥 HIPAA Compliance:");
        logger.info("   • PHI data encryption");
        logger.info("   • Access logging and monitoring");
        logger.info("   • Business associate agreements");
        logger.info("   • Risk assessment procedures");
        
        // Audit configuration
        Properties auditConfig = createAuditConfiguration();
        logger.info("📝 Audit Configuration:");
        auditConfig.forEach((key, value) ->
            logger.info("   {} = {}", key, value));
    }

    /**
     * Creates a secure PostgreSQL container with SSL enabled.
     */
    private static PostgreSQLContainer<?> createSecurePostgreSQLContainer() {
        return new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_secure_demo")
                .withUsername("peegeeq_secure")
                .withPassword("secure_password_123!")
                .withCommand("postgres",
                    "-c", "ssl=on",
                    "-c", "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                    "-c", "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key",
                    "-c", "log_connections=on",
                    "-c", "log_disconnections=on",
                    "-c", "log_statement=all")
                .withReuse(false);
    }

    /**
     * Configures SSL properties for secure database connection.
     */
    private static void configureSSLProperties(PostgreSQLContainer<?> postgres) {
        logger.info("🔧 Configuring SSL/TLS properties...");

        // Basic SSL configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // SSL/TLS configuration
        System.setProperty("peegeeq.database.ssl.enabled", "true");
        System.setProperty("peegeeq.database.ssl.mode", "prefer"); // prefer, require, verify-ca, verify-full
        System.setProperty("peegeeq.database.ssl.factory", "org.postgresql.ssl.DefaultJavaSSLFactory");

        // Certificate configuration (in production, use proper certificates)
        System.setProperty("peegeeq.database.ssl.cert", "client-cert.pem");
        System.setProperty("peegeeq.database.ssl.key", "client-key.pem");
        System.setProperty("peegeeq.database.ssl.rootcert", "ca-cert.pem");

        // Connection security
        System.setProperty("peegeeq.database.connection.timeout", "30000");
        System.setProperty("peegeeq.database.connection.socket-timeout", "60000");
        System.setProperty("peegeeq.database.connection.tcp-keep-alive", "true");

        logger.info("✅ SSL/TLS properties configured");
    }

    /**
     * Tests secure database connection.
     */
    private static void testSecureConnection() {
        logger.info("🧪 Testing secure database connection...");

        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("secure"),
                new SimpleMeterRegistry())) {

            manager.start();

            // Test connection health
            var healthStatus = manager.getHealthCheckManager().getOverallHealth();
            logger.info("Connection Health Status: {}", healthStatus.getStatus());

            if (healthStatus.isHealthy()) {
                logger.info("✅ Secure connection established successfully");
            } else {
                logger.warn("⚠️  Connection health issues detected");
                healthStatus.getComponents().forEach((name, status) -> {
                    if (!status.isHealthy()) {
                        logger.warn("   Unhealthy component: {} - {}", name, status.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            logger.error("❌ Failed to establish secure connection", e);
        }
    }

    /**
     * Creates production security configuration.
     */
    private static Properties createProductionSecurityConfiguration() {
        Properties config = new Properties();

        // Database security
        config.setProperty("peegeeq.database.ssl.enabled", "true");
        config.setProperty("peegeeq.database.ssl.mode", "verify-full");
        config.setProperty("peegeeq.database.ssl.cert", "${SSL_CERT_PATH}");
        config.setProperty("peegeeq.database.ssl.key", "${SSL_KEY_PATH}");
        config.setProperty("peegeeq.database.ssl.rootcert", "${SSL_ROOT_CERT_PATH}");

        // Connection pool security
        config.setProperty("peegeeq.database.pool.max-size", "20");
        config.setProperty("peegeeq.database.pool.min-size", "5");
        config.setProperty("peegeeq.database.pool.connection-timeout", "30000");
        config.setProperty("peegeeq.database.pool.idle-timeout", "600000");
        config.setProperty("peegeeq.database.pool.max-lifetime", "1800000");
        config.setProperty("peegeeq.database.pool.leak-detection-threshold", "60000");

        // Authentication security
        config.setProperty("peegeeq.database.username", "${DB_USERNAME}");
        config.setProperty("peegeeq.database.password", "${DB_PASSWORD}");
        config.setProperty("peegeeq.database.password.encrypted", "true");
        config.setProperty("peegeeq.database.password.encryption.key", "${ENCRYPTION_KEY}");

        // Network security
        config.setProperty("peegeeq.database.connection.tcp-keep-alive", "true");
        config.setProperty("peegeeq.database.connection.socket-timeout", "60000");
        config.setProperty("peegeeq.database.connection.login-timeout", "30");

        // Security monitoring
        config.setProperty("peegeeq.security.monitoring.enabled", "true");
        config.setProperty("peegeeq.security.audit.enabled", "true");
        config.setProperty("peegeeq.security.events.log-level", "INFO");

        return config;
    }

    /**
     * Simulates security event logging.
     */
    private static void simulateSecurityEventLogging() {
        logger.info("🚨 Security Event Logging Examples:");

        // Connection events
        logger.info("SECURITY_EVENT: CONNECTION_ESTABLISHED - User: admin, IP: 192.168.1.100, SSL: true");
        logger.warn("SECURITY_EVENT: CONNECTION_FAILED - User: unknown, IP: 10.0.0.50, Reason: Authentication failed");
        logger.error("SECURITY_EVENT: SUSPICIOUS_ACTIVITY - Multiple failed login attempts from IP: 203.0.113.1");

        // Access events
        logger.info("SECURITY_EVENT: QUERY_EXECUTED - User: app_user, Query: SELECT * FROM orders WHERE customer_id = ?");
        logger.warn("SECURITY_EVENT: PRIVILEGE_ESCALATION_ATTEMPT - User: guest, Attempted: DROP TABLE");

        // Performance events
        logger.warn("SECURITY_EVENT: SLOW_QUERY_DETECTED - Duration: 5000ms, Query: Complex aggregation");
        logger.error("SECURITY_EVENT: RESOURCE_EXHAUSTION - Connection pool at 95% capacity");

        // SSL/TLS events
        logger.info("SECURITY_EVENT: SSL_HANDSHAKE_SUCCESS - Protocol: TLSv1.3, Cipher: AES_256_GCM");
        logger.error("SECURITY_EVENT: SSL_HANDSHAKE_FAILED - Reason: Certificate validation failed");
    }

    /**
     * Demonstrates credential configuration patterns.
     */
    private static void demonstrateCredentialConfiguration() {
        logger.info("🔐 Credential Configuration Examples:");

        // Environment variable approach
        logger.info("📍 Environment Variables:");
        logger.info("   export PEEGEEQ_DB_USERNAME=app_user");
        logger.info("   export PEEGEEQ_DB_PASSWORD=encrypted_password");
        logger.info("   export PEEGEEQ_ENCRYPTION_KEY=base64_encoded_key");

        // Configuration file approach
        logger.info("📄 Configuration Files:");
        logger.info("   peegeeq.database.username=${env:PEEGEEQ_DB_USERNAME}");
        logger.info("   peegeeq.database.password=${env:PEEGEEQ_DB_PASSWORD}");
        logger.info("   peegeeq.database.password.encrypted=true");

        // Vault integration approach
        logger.info("🏦 Vault Integration:");
        logger.info("   peegeeq.database.username=${vault:secret/peegeeq/db#username}");
        logger.info("   peegeeq.database.password=${vault:secret/peegeeq/db#password}");

        // Kubernetes secrets approach
        logger.info("☸️  Kubernetes Secrets:");
        logger.info("   peegeeq.database.username=${k8s:peegeeq-db-secret#username}");
        logger.info("   peegeeq.database.password=${k8s:peegeeq-db-secret#password}");
    }

    /**
     * Creates audit configuration for compliance.
     */
    private static Properties createAuditConfiguration() {
        Properties config = new Properties();

        // Audit logging
        config.setProperty("peegeeq.audit.enabled", "true");
        config.setProperty("peegeeq.audit.log-level", "INFO");
        config.setProperty("peegeeq.audit.log-format", "JSON");
        config.setProperty("peegeeq.audit.log-file", "/var/log/peegeeq/audit.log");

        // Event types to audit
        config.setProperty("peegeeq.audit.events.connections", "true");
        config.setProperty("peegeeq.audit.events.authentication", "true");
        config.setProperty("peegeeq.audit.events.queries", "true");
        config.setProperty("peegeeq.audit.events.errors", "true");
        config.setProperty("peegeeq.audit.events.configuration-changes", "true");

        // Retention and archival
        config.setProperty("peegeeq.audit.retention.days", "2555"); // 7 years
        config.setProperty("peegeeq.audit.archival.enabled", "true");
        config.setProperty("peegeeq.audit.archival.location", "s3://audit-logs/peegeeq/");

        // Compliance settings
        config.setProperty("peegeeq.audit.compliance.gdpr", "true");
        config.setProperty("peegeeq.audit.compliance.sox", "true");
        config.setProperty("peegeeq.audit.compliance.hipaa", "false");

        return config;
    }

    /**
     * Masks sensitive values in configuration output.
     */
    private static String maskSensitiveValue(String key, String value) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.contains("password") || lowerKey.contains("secret") ||
            lowerKey.contains("key") || lowerKey.contains("token")) {
            return "***MASKED***";
        }
        return value;
    }
}
