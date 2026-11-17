package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The one definition of how the adapters read PostgreSQL's configuration file errors before and after a reload (plan
 * §9.33, contract 1 and RS1b). The errors are told apart by columns of {@code pg_file_settings}, {@code pg_hba_file_rules},
 * and {@code pg_settings}, not by message text, which PostgreSQL can translate. Both file views read the files when they
 * are queried, so they report an error before the reload.
 */
final class PgConfigurationFiles {
    private static final String ENTRY = "coalesce(f.name, '(no parameter)') || ' in ' || coalesce(f.sourcefile, '?') "
        + "|| ' line ' || coalesce(f.sourceline::text, '?') || ': ' || f.error";
    /**
     * Three lists. {@code rejected}: an entry with no parameter name (a syntax error) or a name {@code pg_settings} does
     * not know; PostgreSQL then applies no change of the file and leaves the load time unchanged (check 6).
     * {@code hba}: a {@code pg_hba.conf} rule; PostgreSQL then keeps its previous rules (check 7). {@code invalid}: an
     * entry of a known parameter that is not pending a restart, which after a reload is an invalid value (check 8);
     * before a reload a valid pending restart is in this list too. No double quote: Windows drops it from an argument.
     */
    static final String ERRORS = "select json_build_object("
        + "'rejected', coalesce((select json_agg(" + ENTRY + " order by f.seqno) from pg_file_settings f "
        + "where f.error is not null and (f.name is null "
        + "or not exists (select 1 from pg_settings s where s.name = lower(f.name)))), '[]'::json), "
        + "'hba', coalesce((select json_agg('pg_hba.conf line ' || coalesce(line_number::text, '?') || ': ' || error "
        + "order by line_number) from pg_hba_file_rules where error is not null), '[]'::json), "
        + "'invalid', coalesce((select json_agg(" + ENTRY + " order by f.seqno) from pg_file_settings f "
        + "join pg_settings s on s.name = lower(f.name) where f.error is not null and not s.pending_restart), "
        + "'[]'::json))";

    private PgConfigurationFiles() {
    }

    /** The three lists of {@link #ERRORS}. */
    record Errors(List<Object> rejected, List<Object> hba, List<Object> invalid) {
        Errors {
            rejected = List.copyOf(rejected);
            hba = List.copyOf(hba);
            invalid = List.copyOf(invalid);
        }

        /** The errors for which PostgreSQL applies none of a file, or keeps its previous {@code pg_hba.conf} rules. */
        List<Object> wholeFile() {
            List<Object> errors = new ArrayList<>(rejected);
            errors.addAll(hba);
            return List.copyOf(errors);
        }
    }

    /** Parses the output of {@link #ERRORS}. Output that is not that observation fails with an {@link IllegalArgumentException}. */
    static Errors parse(String output) {
        JsonObject observed = new JsonObject(output);
        if (!(observed.getValue("rejected") instanceof JsonArray rejected)
                || !(observed.getValue("hba") instanceof JsonArray hba)
                || !(observed.getValue("invalid") instanceof JsonArray invalid)) {
            throw new IllegalArgumentException("Not an observation of configuration file errors");
        }
        return new Errors(rejected.stream().toList(), hba.stream().toList(), invalid.stream().toList());
    }
}
