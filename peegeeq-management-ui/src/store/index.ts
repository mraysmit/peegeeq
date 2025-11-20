/**
 * Redux Store Configuration
 */
import { configureStore } from '@reduxjs/toolkit';
import { setupListeners } from '@reduxjs/toolkit/query';
import { queuesApi } from './api/queuesApi';

export const store = configureStore({
  reducer: {
    // Add the RTK Query API reducer
    [queuesApi.reducerPath]: queuesApi.reducer,
  },
  // Add the RTK Query middleware
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(queuesApi.middleware),
});

// Setup listeners for refetchOnFocus/refetchOnReconnect
setupListeners(store.dispatch);

// Export types for TypeScript
export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
