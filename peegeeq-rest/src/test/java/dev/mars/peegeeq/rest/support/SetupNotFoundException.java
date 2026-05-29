package dev.mars.peegeeq.rest.support;

/**
 * Test-support exception whose simple class name is exactly "SetupNotFoundException".
 *
 * DatabaseSetupHandler.isSetupNotFoundError() checks:
 *   throwable.getClass().getSimpleName().equals("SetupNotFoundException")
 *
 * A plain RuntimeException will NOT trigger the 404 path  it produces 503.
 * This class must live in test scope and must not be renamed.
 */
public class SetupNotFoundException extends RuntimeException {
    public SetupNotFoundException(String message) {
        super(message);
    }
}
