package vn.gk.customer.accounts;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class GoogleHttpTest {
 @Test void configAndLoginUseNonceCookieAndNormalCustomerSession() throws Exception {
  var auth=mock(CustomerAuth.class);when(auth.user(nullable(String.class))).thenReturn(null);var google=mock(GoogleIdentity.class);var service=mock(vn.gk.customer.bookings.BookingService.class);var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var mvc=MockMvcBuilders.standaloneSetup(new vn.gk.customer.api.CustomerController(auth,service,jdbc,google)).setControllerAdvice(new vn.gk.customer.api.CustomerErrors()).addFilters(new vn.gk.customer.security.CustomerFilter(auth)).build();
  mvc.perform(get("/api/customer/auth/google/config").header("X-GK-Portal","customer")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  when(google.enabled()).thenReturn(true);when(google.clientId()).thenReturn("123-test.apps.googleusercontent.com");when(auth.googleNonce()).thenReturn("n".repeat(43));
  var config=mvc.perform(get("/api/customer/auth/google/config").header("X-GK-Portal","customer")).andExpect(status().isOk()).andReturn();String cookie=config.getResponse().getHeader("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("gk_google_nonce="));
  mvc.perform(post("/api/customer/auth/google").header("X-GK-Portal","customer").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"mock\"}")).andExpect(status().isForbidden());verify(google,never()).verify(any(),any());
  var profile=new GoogleIdentity.Profile("sub-1","qa@gmail.com","Nguyễn Minh Anh");when(google.verify("mock","n".repeat(43))).thenReturn(profile);when(auth.googleLogin(eq(profile),anyString(),isNull(),isNull(),isNull())).thenReturn(new CustomerAuth.GoogleResult(null,profile.name(),profile.email()));
  mvc.perform(post("/api/customer/auth/google").header("X-GK-Portal","customer").header("X-GK","1").cookie(new Cookie(CustomerAuth.GOOGLE_NONCE,"n".repeat(43))).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"mock\"}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.requiresProfile").value(true)).andExpect(header().doesNotExist("Set-Cookie"));
  when(auth.googleLogin(eq(profile),anyString(),isNull(),isNull(),isNull())).thenReturn(new CustomerAuth.GoogleResult("new-session",null,null));when(auth.user("new-session")).thenReturn(Map.of("id",UUID.randomUUID(),"role","customer","display_name",profile.name(),"phone","0909000001"));
  var response=mvc.perform(post("/api/customer/auth/google").header("X-GK-Portal","customer").header("X-GK","1").cookie(new Cookie(CustomerAuth.GOOGLE_NONCE,"n".repeat(43))).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"mock\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("customer")).andReturn().getResponse();
  assertEquals(2,response.getHeaders("Set-Cookie").size());assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c->c.startsWith("gk_customer_session=new-session")&&c.contains("HttpOnly")));assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c->c.startsWith("gk_google_nonce=")&&c.contains("Max-Age=0")));
 }
}
