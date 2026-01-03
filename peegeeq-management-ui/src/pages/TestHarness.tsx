import React from 'react';
import { useSearchParams } from 'react-router-dom';
import EventVisualization from '../components/EventVisualization';

const TestHarness: React.FC = () => {
    const [searchParams] = useSearchParams();
    const setupId = searchParams.get('setupId') || 'test-setup';
    const eventStoreName = searchParams.get('eventStoreName') || 'test-store';

    return (
        <div style={{ padding: 20 }}>
            <h1>Test Harness</h1>
            <div data-testid="test-harness-container">
                <EventVisualization 
                    setupId={setupId} 
                    eventStoreName={eventStoreName} 
                />
            </div>
        </div>
    );
};

export default TestHarness;
