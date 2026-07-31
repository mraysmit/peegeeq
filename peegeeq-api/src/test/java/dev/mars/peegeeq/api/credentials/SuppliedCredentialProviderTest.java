package dev.mars.peegeeq.api.credentials;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class SuppliedCredentialProviderTest {

    @Test
    void resolvePasswordFailsWithActionableMessage() {
        CredentialProvider provider = new SuppliedCredentialProvider();

        Future<String> future = provider.resolvePassword("vault://prod/orders-db");

        assertTrue(future.failed(), "supplied-at-connect provider must not resolve credential references");
        assertInstanceOf(IllegalStateException.class, future.cause());
        String message = future.cause().getMessage();
        assertTrue(message.contains("vault://prod/orders-db"),
                "failure message should name the unresolvable reference, got: " + message);
        assertTrue(message.contains("CredentialProvider"),
                "failure message should tell the caller to configure a CredentialProvider, got: " + message);
    }

    @Test
    void resolvePasswordWithNullRefFailsWithoutNullPointer() {
        CredentialProvider provider = new SuppliedCredentialProvider();

        Future<String> future = provider.resolvePassword(null);

        assertTrue(future.failed(), "a null reference must fail, not resolve");
        assertInstanceOf(IllegalStateException.class, future.cause());
        assertNotNull(future.cause().getMessage(), "failure must carry an actionable message");
    }
}
