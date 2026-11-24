package vn.gk.logistics.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

@Component("schemaUpgrade")
public class SchemaUpgrade {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public SchemaUpgrade(DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.dataSource=dataSource; this.jdbc=jdbc; this.tx=tx;
    }
    @PostConstruct
    public void migrate() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(7462003)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version int PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
            for (var migration : java.util.Map.ofEntries(java.util.Map.entry(3,"db/003_dynamic_routing.sql"),java.util.Map.entry(4,"db/004_dispatch_planning.sql"),java.util.Map.entry(5,"db/005_customer_tracking.sql"),java.util.Map.entry(6,"db/006_dispatcher_workspace.sql"),java.util.Map.entry(7,"db/007_order_lifecycle.sql"),java.util.Map.entry(8,"db/008_workday_reservations.sql"),java.util.Map.entry(9,"db/009_vehicle_maintenance.sql"),java.util.Map.entry(10,"db/010_maintenance_review.sql"),java.util.Map.entry(11,"db/011_shipping_pricing.sql"),java.util.Map.entry(12,"db/012_input_constraints.sql"),java.util.Map.entry(13,"db/013_pickup_returns.sql"),java.util.Map.entry(14,"db/014_finance.sql"),java.util.Map.entry(15,"db/015_payments_fixed_salary.sql"),java.util.Map.entry(16,"db/016_payment_notifications.sql"),java.util.Map.entry(17,"db/017_payment_decisions_pricing.sql"),java.util.Map.entry(18,"db/018_fleet_management.sql"),java.util.Map.entry(19,"db/019_batch_pickup_policy.sql"),java.util.Map.entry(20,"db/020_order_bulk_reschedule.sql"),java.util.Map.entry(21,"db/021_admin_assistant.sql"),java.util.Map.entry(22,"db/022_archive_legacy_tables.sql"),java.util.Map.entry(23,"db/023_maintenance_receipts.sql")).entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                if (jdbc.queryForObject("SELECT count(*) FROM schema_migrations WHERE version=?",Integer.class,migration.getKey())>0) continue;
                var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
                try {
                    var resource=new ClassPathResource(migration.getValue());
                    // PostgreSQL function bodies contain semicolons. Execute this
                    // migration intact instead of splitting the PL/pgSQL body.
                    if(migration.getKey()>=5) ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.support.EncodedResource(resource),false,false,"--",ScriptUtils.EOF_STATEMENT_SEPARATOR,"/*","*/");
                    else ScriptUtils.executeSqlScript(connection,resource);
                }
                finally { org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,dataSource); }
                jdbc.update("INSERT INTO schema_migrations(version) VALUES(?)",migration.getKey());
            }
        });
    }
}
