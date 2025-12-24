-- Create tenant schema with LOG-level logging
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = '{schema}') THEN
        CREATE SCHEMA {schema};
        RAISE LOG 'Schema created: {schema}' USING DETAIL = 'PGQINF0550';
    ELSE
        RAISE LOG 'Schema already exists: {schema}' USING DETAIL = 'PGQINF0551';
    END IF;
END
$$;
