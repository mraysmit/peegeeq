
const API_URL = 'http://localhost:8080';

async function run() {
    const setupId = 'verify-api-' + Date.now();
    const storeName = 'verify-store';
    const correlationId = 'corr-' + Date.now();

    console.log(`Creating setup ${setupId}...`);
    const setupRes = await fetch(`${API_URL}/api/v1/database-setup/create`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            setupId,
            description: 'Verification Setup',
            databaseConfig: {
                host: 'localhost',
                port: 5432,
                database: 'postgres',
                username: 'postgres',
                password: 'password'
            },
            eventStores: [{ eventStoreName: storeName, biTemporalEnabled: true }]
        })
    });

    if (!setupRes.ok) {
        console.error('Failed to create setup:', await setupRes.text());
        return;
    }
    console.log('Setup created.');

    console.log(`Posting events with correlationId=${correlationId}...`);
    
    // Post Root Event
    await postEvent(setupId, storeName, {
        eventType: 'RootEvent',
        aggregateId: 'agg-1',
        correlationId: correlationId,
        eventData: { msg: 'root' }
    });

    // Post Child Event
    await postEvent(setupId, storeName, {
        eventType: 'ChildEvent',
        aggregateId: 'agg-1',
        correlationId: correlationId,
        eventData: { msg: 'child' }
    });

    console.log('Events posted. Querying by correlationId...');

    // Query
    const params = new URLSearchParams({
        correlationId: correlationId,
        limit: '1000',
        offset: '0',
        sortOrder: 'TRANSACTION_TIME_ASC',
        includeCorrections: 'true'
    });

    const queryUrl = `${API_URL}/api/v1/eventstores/${setupId}/${storeName}/events?${params.toString()}`;
    console.log(`GET ${queryUrl}`);

    const queryRes = await fetch(queryUrl);
    
    if (!queryRes.ok) {
        console.error('Query failed:', queryRes.status, await queryRes.text());
    } else {
        const data = await queryRes.json();
        console.log('Query Result:', JSON.stringify(data, null, 2));
        
        if (data.events && data.events.length === 2) {
            console.log('SUCCESS: Found 2 events.');
        } else {
            console.error(`FAILURE: Expected 2 events, found ${data.events ? data.events.length : 0}`);
        }
    }

    // Cleanup
    console.log('Cleaning up...');
    await fetch(`${API_URL}/api/v1/database-setup/${setupId}`, { method: 'DELETE' });
}

async function postEvent(setupId, storeName, event) {
    const res = await fetch(`${API_URL}/api/v1/eventstores/${setupId}/${storeName}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(event)
    });
    if (!res.ok) {
        throw new Error(`Failed to post event: ${res.status} ${await res.text()}`);
    }
    return res.json();
}

run().catch(console.error);
