package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.events.ExceptionManagedEvent;
import dev.mars.peegeeq.examples.fundscustody.events.ManualRepairEvent;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Service for managing operational exceptions and manual repairs.
 * Demonstrates the Operational Events Domain from the Financial Services Event Catalogue.
 */
public class ExceptionManagementService {
    private static final Logger logger = LoggerFactory.getLogger(ExceptionManagementService.class);
    
    private final EventStore<ExceptionManagedEvent> exceptionStore;
    private final EventStore<ManualRepairEvent> repairStore;

    public ExceptionManagementService(
            EventStore<ExceptionManagedEvent> exceptionStore,
            EventStore<ManualRepairEvent> repairStore) {
        this.exceptionStore = exceptionStore;
        this.repairStore = repairStore;
    }

    /**
     * Records an automated exception detection (e.g. settlement failure).
     * 
     * @param event The exception payload
     * @param validTime The business effective time of the exception
     * @return future containing the stored event
     */
    public Future<BiTemporalEvent<ExceptionManagedEvent>> recordException(ExceptionManagedEvent event, Instant validTime) {
        logger.info("Recording automated exception {} for transaction {}", 
            event.exceptionId(), event.sourceTransactionId());
            
        return exceptionStore.append(
            "exception.detection.automated",
            event,
            validTime,
            Map.of("severity", event.severity(), "type", event.exceptionType()),
            null,
            null,
            "EXCEPTION:" + event.exceptionId() // Aggregate by exception ID
        );
    }

    /**
     * Records a manual repair executed by a human operator.
     * 
     * @param event The manual repair payload
     * @param validTime The business effective time of the repair
     * @return future containing the stored event
     */
    public Future<BiTemporalEvent<ManualRepairEvent>> recordManualRepair(ManualRepairEvent event, Instant validTime) {
        logger.info("Recording manual repair {} executed by {} for transaction {}", 
            event.repairId(), event.executedBy(), event.originalTransactionId());
            
        return repairStore.append(
            "repair.manual.executed",
            event,
            validTime,
            Map.of("repairType", event.repairType(), "executedBy", event.executedBy()),
            null,
            null,
            "REPAIR:" + event.repairId() // Aggregate by repair ID
        );
    }
}
