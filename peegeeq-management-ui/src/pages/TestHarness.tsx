import React from 'react';

const TestHarness: React.FC = () => {
    return (
        <div style={{ padding: 20 }}>
            <h1>Test Harness</h1>
            <div data-testid="test-harness-container">
                <p>Test harness — use page routes directly for isolated tests.</p>
            </div>
        </div>
    );
};

export default TestHarness;
