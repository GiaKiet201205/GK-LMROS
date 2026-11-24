package vn.gk.logistics.finance;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import java.util.UUID;
@Service
@DependsOn("schemaUpgrade")
public class FixedSalaryService {
 private final JdbcTemplate jdbc;private final WorkDayPolicy policy;private final boolean enabled;
 public FixedSalaryService(JdbcTemplate jdbc,WorkDayPolicy policy,@Value("${FINANCE_ACCRUAL_ENABLED:true}") boolean enabled){this.jdbc=jdbc;this.policy=policy;this.enabled=enabled;}
 @Transactional
 @Scheduled(fixedDelayString="${FINANCE_ACCRUAL_MS:60000}",initialDelay=1500)
 public void tick(){if(enabled)try{accrue();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Salary accrual will retry ({})",e.getClass().getSimpleName());}}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void accrue(){if(!enabled)return;jdbc.execute("SELECT pg_advisory_xact_lock(7462014)");
  jdbc.update("WITH expected AS (SELECT d.id AS driver_id,d.warehouse_id,g.day::date AS day,coalesce(r.monthly_amount,15000000) AS salary,extract(day FROM g.day)::int AS n,extract(day FROM (date_trunc('month',g.day)+interval '1 month - 1 day'))::int AS days FROM drivers d CROSS JOIN LATERAL generate_series(greatest('2026-09-01'::date,d.joined_on),?::date,interval '1 day') g(day) LEFT JOIN LATERAL(SELECT monthly_amount FROM driver_salary_rates WHERE driver_id=d.id AND effective_month<=date_trunc('month',g.day)::date ORDER BY effective_month DESC LIMIT 1)r ON true WHERE (d.archived_at IS NULL OR g.day::date<=(d.archived_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) AND (EXISTS(SELECT 1 FROM driver_service_periods p WHERE p.driver_id=d.id AND g.day::date>=p.started_on AND (p.ended_on IS NULL OR g.day::date<=p.ended_on)) OR NOT EXISTS(SELECT 1 FROM driver_service_periods p WHERE p.driver_id=d.id)) AND NOT EXISTS(SELECT 1 FROM driver_payrolls old WHERE old.driver_id=d.id AND old.period=date_trunc('month',g.day)::date AND old.status='approved' AND old.salary_mode='daily_legacy')),booked AS (SELECT driver_id,entry_date,sum(expense) AS amount FROM finance_ledger WHERE category='salary' AND payroll_id IS NULL GROUP BY driver_id,entry_date),delta AS (SELECT e.*,round(salary::numeric*n/days)-round(salary::numeric*(n-1)/days)-coalesce(b.amount,0) AS amount FROM expected e LEFT JOIN booked b ON b.driver_id=e.driver_id AND b.entry_date=e.day) INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,driver_id,description) SELECT ?||':'||driver_id||':'||day,day,CASE WHEN amount>0 THEN 'expense' ELSE 'income' END,'salary',abs(amount),warehouse_id,driver_id,'Phân bổ lương cố định tháng '||to_char(day,'MM/YYYY') FROM delta WHERE amount<>0",policy.today(),"salary-accrual:"+UUID.randomUUID());
 }
}
