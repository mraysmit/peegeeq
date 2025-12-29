CREATE TRIGGER "trigger_{tableName}_notify"
    AFTER INSERT ON {schema}."{tableName}"
    FOR EACH ROW EXECUTE FUNCTION {schema}."notify_{tableName}_events"();
