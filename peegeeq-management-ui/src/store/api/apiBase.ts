/**
 * Shared base query configuration for RTK Query
 */
import { fetchBaseQuery, BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query/react';
import { getBackendConfig } from '../../services/configService';
import { API_PREFIX } from '../../services/apiConstants';

// Dynamic base query that reads config on each request and applies version prefix
export const dynamicBaseQuery: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> = async (
    args,
    api,
    extraOptions
) => {
    const config = getBackendConfig();
    // Ensure we use the API prefix (/api/v1) for all RTK query requests
    const baseUrl = `${config.apiUrl.replace(/\/$/, '')}${API_PREFIX}`;

    const rawBaseQuery = fetchBaseQuery({ baseUrl });
    return rawBaseQuery(args, api, extraOptions);
};
