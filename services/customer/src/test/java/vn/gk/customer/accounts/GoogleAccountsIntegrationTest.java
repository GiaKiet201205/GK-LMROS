package vn.gk.customer.accounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"app.internal-token=google-test-token","CUSTOMER_GOOGLE_CLIENT_ID="})
@EnabledIfEnvironmentVariable(named="GK_CUSTOMER_GOOGLE_TEST_DB",matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test")
@Transactional
class GoogleAccountsIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("GK_CUSTOMER_GOOGLE_TEST_DB");if(url==null||!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test"))throw new IllegalStateException("Dedicated test database only");r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"gk");r.add("spring.datasource.password",()->"test");}
 @MockBean vn.gk.customer.bookings.BookingService bookings;
 @Autowired CustomerAuth auth;@Autowired JdbcTemplate jdbc;
 GoogleIdentity.Profile profile(String sub){return new GoogleIdentity.Profile(sub,"qa@gmail.com","Nguyễn Minh Anh");}
 @Test void newGoogleAccountRequiresPhoneAndReusesSameCustomer(){String nonce=auth.googleNonce();assertNull(auth.googleLogin(profile("qa-new"),nonce,null,null,null).token());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customers WHERE google_sub='qa-new'",Integer.class));var result=auth.googleLogin(profile("qa-new"),nonce,"Nguyễn Minh Anh","0909000011",null);var first=auth.user(result.token());assertEquals("0909000011",first.get("phone"));assertEquals(true,first.get("google_linked"));assertNull(auth.login("0909000011","random-password"));var again=auth.googleLogin(profile("qa-new"),auth.googleNonce(),null,null,null);assertEquals(first.get("id"),auth.user(again.token()).get("id"));}
 @Test void passwordProofLinksExistingCustomerAndPreservesBookings(){String existing=auth.register("Nguyễn Minh Anh","0909000022","Existing@123");UUID id=(UUID)auth.user(existing).get("id");UUID booking=UUID.randomUUID();jdbc.update("INSERT INTO bookings(id,customer_id,request_key,payload) VALUES(?,?,?,'{}')",booking,id,UUID.randomUUID());var linked=auth.googleLogin(profile("qa-link"),auth.googleNonce(),"Tên khác","0909000022","Existing@123");assertEquals(id,auth.user(linked.token()).get("id"));assertEquals(id,jdbc.queryForObject("SELECT customer_id FROM bookings WHERE id=?",UUID.class,booking));assertNotNull(auth.login("0909000022","Existing@123"));}
 @Test void cannotClaimExistingPhoneWithoutCorrectPassword(){auth.register("Trần Gia Bảo","0909000033","Existing@123");assertEquals(409,assertThrows(ResponseStatusException.class,()->auth.googleLogin(profile("qa-wrong"),auth.googleNonce(),"Trần Gia Bảo","0909000033","wrong")).getStatusCode().value());}
 @Test void rejectsNonceReplayAndExpiredChallenge(){var p=profile("qa-replay");String nonce=auth.googleNonce();auth.googleLogin(p,nonce,"Lê Minh Anh","0909000044",null);assertEquals(401,assertThrows(ResponseStatusException.class,()->auth.googleLogin(p,nonce,null,null,null)).getStatusCode().value());}
 @Test void databaseMigrationKeepsPhonePasswordAccountsValid(){assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_schema_migrations WHERE version=2",Integer.class));String session=auth.register("Võ Minh An","0909000055","Normal@123");assertNotNull(auth.user(session));assertNotNull(auth.login("0909000055","Normal@123"));}
 @Test void expiredChallengeAndOtherGoogleLinkAreRejected(){String nonce=auth.googleNonce();jdbc.update("UPDATE google_login_challenges SET expires_at=now()-interval '1 second'");assertEquals(401,assertThrows(ResponseStatusException.class,()->auth.googleLogin(profile("qa-expired"),nonce,"Nguyễn Minh Anh","0909000066",null)).getStatusCode().value());}
 @Test void cannotReplaceExistingGoogleLink(){auth.register("Nguyễn Minh Anh","0909000077","Existing@123");auth.googleLogin(profile("qa-original"),auth.googleNonce(),"Nguyễn Minh Anh","0909000077","Existing@123");assertEquals(409,assertThrows(ResponseStatusException.class,()->auth.googleLogin(profile("qa-other"),auth.googleNonce(),"Nguyễn Minh Anh","0909000077","Existing@123")).getStatusCode().value());}

}
