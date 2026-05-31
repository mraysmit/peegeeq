/**
 * Redux Store Configuration
 */
import { configureStore, createSlice } from '@reduxjs/toolkit';

// Placeholder slice — app state is managed by Zustand (utilitiesStore).
// Redux Provider is kept in main.tsx for future use; this slice satisfies
// the "must have at least one reducer" requirement.
const appSlice = createSlice({
  name: 'app',
  initialState: {},
  reducers: {},
});

export const store = configureStore({
  reducer: {
    app: appSlice.reducer,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
