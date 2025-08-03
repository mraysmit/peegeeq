package dev.mars.peegeeq.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Test class for PeeGeeQExampleRunner to verify formatting and basic functionality.
 */
public class PeeGeeQExampleRunnerTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    public void restoreStreams() {
        System.setOut(originalOut);
    }

    @Test
    public void testListExamples() {
        // Test that the --list command works without errors
        String[] args = {"--list"};
        
        try {
            PeeGeeQExampleRunner.main(args);
            
            // Verify that the output contains expected content
            String output = outContent.toString();
            assert output.contains("Available PeeGeeQ Examples:");
            assert output.contains("self-contained");
            assert output.contains("RECOMMENDED FIRST");
            
            System.out.println("✓ List examples test passed");
        } catch (Exception e) {
            System.err.println("✗ List examples test failed: " + e.getMessage());
            throw e;
        }
    }
}
