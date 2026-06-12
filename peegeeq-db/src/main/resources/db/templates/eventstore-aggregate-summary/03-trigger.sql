CREATE TRIGGER "trigger_{tableName}_aggregate_summary"
    AFTER INSERT ON {schema}."{tableName}"
    FOR EACH ROW EXECUTE FUNCTION {schema}."maintain_{tableName}_aggregate_summary"();
