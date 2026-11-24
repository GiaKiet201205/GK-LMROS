package vn.gk.logistics.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"ROUTING_ENABLED=false","RESERVATION_RECONCILE_ENABLED=false","FINANCE_ACCRUAL_ENABLED=false","app.internal-token=assistant-test-token","GK_ASSISTANT_ENABLED=false","logging.level.root=WARN"})
@EnabledIfEnvironmentVariable(named="GK_ASSISTANT_TEST_DB",matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test")
class AssistantIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("GK_ASSISTANT_TEST_DB");if(url==null||!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test"))throw new IllegalStateException("Dedicated test database only");r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"gk");r.add("spring.datasource.password",()->"test");}
 @MockBean vn.gk.logistics.infrastructure.Events outbound;
 @Autowired AssistantKnowledge knowledge;@Autowired AssistantContext context;@Autowired JdbcTemplate jdbc;
 @Test void migrationKnowledgeVersioningAndLiveQueriesDoNotModifyBusinessRows(){
  var before=jdbc.queryForMap("SELECT (SELECT count(*) FROM orders) orders,(SELECT count(*) FROM trips) trips,(SELECT count(*) FROM finance_entries) entries");
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migrations WHERE version=21",Integer.class));
  var body=new AssistantKnowledge.Write("Hướng dẫn QA lấy hàng","/distribution","Quy trình qatestuniquekey: xác nhận khách trước khi lấy.",null);long id=((Number)knowledge.save(null,body,100).get("id")).longValue();
  try{assertTrue(knowledge.search("qatestuniquekey","/distribution").stream().anyMatch(d->d.id().equals("custom:"+id)));knowledge.save(id,new AssistantKnowledge.Write(body.title(),body.path(),body.content()+" Đã cập nhật.",1L),100);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->knowledge.save(id,new AssistantKnowledge.Write(body.title(),body.path(),body.content(),1L),100));
   var rows=context.live("Tổng quan đơn xe tài xế, bảng giá cước kg, lợi nhuận chi phí tháng 9/2026 và đơn GK-QATEST-001",null);assertTrue(rows.stream().anyMatch(s->s.id().equals("finance-live")));assertTrue(rows.stream().anyMatch(s->s.id().equals("pricing-live")));assertTrue(rows.stream().anyMatch(s->s.id().equals("fleet-live")));assertTrue(rows.stream().anyMatch(s->s.id().equals("missing-GK-QATEST-001")));
   Integer wh=jdbc.queryForObject("SELECT min(id) FROM warehouses WHERE archived_at IS NULL",Integer.class);if(wh!=null)assertFalse(context.live("xe đơn cước doanh thu",wh).isEmpty());assertThrows(IllegalArgumentException.class,()->context.live("đơn",Integer.MAX_VALUE));
   assertEquals(before,jdbc.queryForMap("SELECT (SELECT count(*) FROM orders) orders,(SELECT count(*) FROM trips) trips,(SELECT count(*) FROM finance_entries) entries"));
  }finally{knowledge.delete(id,2,100);assertTrue(knowledge.all().stream().noneMatch(d->d.id().equals("custom:"+id)));}
 }
 @Test @org.springframework.transaction.annotation.Transactional
 void exactOrderLookupIsScopedAndDoesNotExposeContactDetails(){
  Integer warehouse=jdbc.queryForObject("SELECT min(id) FROM warehouses WHERE archived_at IS NULL",Integer.class);if(warehouse==null)return;
  Integer id=jdbc.queryForObject("INSERT INTO orders(code,customer_name,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,warehouse_id,status,stage,in_warehouse) VALUES('GK-QATEST-FOUND','Tên khách bí mật QA','Địa chỉ QA',10.75,106.65,2,.01,'2026-11-23 08:00','2026-11-23 20:00',?,'pending','awaiting_pickup',false) RETURNING id",Integer.class,warehouse);
  var result=context.live("Tra đơn GK-QATEST-FOUND",warehouse);var source=result.stream().filter(r->r.id().equals("order-"+id)).findFirst().orElseThrow();assertTrue(source.content().contains("awaiting_pickup"));assertFalse(source.content().contains("Tên khách bí mật QA"));assertEquals("/orders/"+id,source.path());
  assertTrue(context.live("doanh thu 12/2099",null).stream().anyMatch(r->r.content().contains("chưa bắt đầu")));
 }

}
