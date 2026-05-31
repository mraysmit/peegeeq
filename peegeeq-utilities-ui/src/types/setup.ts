export interface DatabaseConfig {
  host: string
  port: number
  databaseName: string
  username: string
  password: string
  schema: string
  sslEnabled: boolean
  templateDatabase: 'template0'
  encoding: 'UTF8'
}

export interface CreateSetupRequest {
  setupId: string
  databaseConfig: DatabaseConfig
  queues: []
  eventStores: []
}

export interface SetupDetails {
  queueFactories?: string[]
  eventStores?: string[]
  status?: string
}

export interface SetupSummary {
  setupId: string
  queues: number
  eventStores: number
  status: string
}

export const DEFAULT_DATABASE_CONFIG: Omit<DatabaseConfig, 'databaseName' | 'password'> = {
  host: 'localhost',
  port: 5432,
  username: 'peegeeq',
  schema: 'public',
  sslEnabled: false,
  templateDatabase: 'template0',
  encoding: 'UTF8',
}
