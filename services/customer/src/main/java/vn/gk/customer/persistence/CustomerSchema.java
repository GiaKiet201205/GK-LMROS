package vn.gk.customer.persistence;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

@Component("customerSchema")
public class CustomerSchema {
    private final JdbcTemplate jdbc;private final DataSource source;private final TransactionTemplate tx;
    public CustomerSchema(JdbcTemplate jdbc,DataSource source,TransactionTemplate tx) { this.jdbc=jdbc;this.source=source;this.tx=tx; }
    @PostConstruct public void migrate() {
        tx.executeWithoutResult(s->{
            jdbc.execute("SELECT pg_advisory_xact_lock(7462101)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS customer_schema_migrations(version int PRIMARY KEY)");
            if(jdbc.queryForObject("SELECT count(*) FROM customer_schema_migrations WHERE version=1",Integer.class)==0) {
            var connection=DataSourceUtils.getConnection(source);
            try { ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema.sql")); }
            finally { DataSourceUtils.releaseConnection(connection,source); }
            jdbc.update("INSERT INTO customer_schema_migrations VALUES(1)");
            }
            if(jdbc.queryForObject("SELECT count(*) FROM customer_schema_migrations WHERE version=2",Integer.class)==0) {
                var connection=DataSourceUtils.getConnection(source);
                try { ScriptUtils.executeSqlScript(connection,new ClassPathResource("google-auth.sql")); }
                finally { DataSourceUtils.releaseConnection(connection,source); }
                jdbc.update("INSERT INTO customer_schema_migrations VALUES(2)");
            }
        });
    }
}
