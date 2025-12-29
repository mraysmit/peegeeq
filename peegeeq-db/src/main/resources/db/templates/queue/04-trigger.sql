CREATE TRIGGER "trigger_{queueName}_notify"
    AFTER INSERT OR UPDATE OR DELETE ON {schema}."{queueName}"
    FOR EACH ROW EXECUTE FUNCTION {schema}."notify_{queueName}_changes"();
