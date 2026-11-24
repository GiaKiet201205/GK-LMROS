package vn.gk.logistics.security;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FinanceAccessTest {
 @Test void driverCanReadOnlyHisPayslipsAndCannotReadCompanyFinance() throws Exception {
  for(String path:new String[]{"/api/finance/report","/api/finance/entries","/api/finance/resources","/api/finance/payrolls","/api/finance/mine"}){
   var auth=mock(AuthService.class);when(auth.user(any())).thenReturn(Map.of("role","driver","driver_id",1));var filter=new AuthFilter(auth,mock(JdbcTemplate.class));
   var req=new MockHttpServletRequest("GET",path);req.addHeader("X-GK-Portal","driver");var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);
   if(path.endsWith("/mine")){assertNotNull(chain.getRequest());assertEquals(200,res.getStatus());}else{assertNull(chain.getRequest());assertEquals(403,res.getStatus());}
  }
 }
 @Test void driverCannotWriteFinanceAndStaffWritesRequireCsrfHeader() throws Exception {
  for(String role:new String[]{"driver","dispatcher"})for(boolean csrf:new boolean[]{false,true}){
   var auth=mock(AuthService.class);when(auth.user(any())).thenReturn(Map.of("role",role,"driver_id",1));var filter=new AuthFilter(auth,mock(JdbcTemplate.class));
   var req=new MockHttpServletRequest("POST","/api/finance/expenses");req.addHeader("X-GK-Portal",role);if(csrf)req.addHeader("X-GK","1");var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);
   assertEquals(role.equals("dispatcher")&&csrf,chain.getRequest()!=null);
  }
 }
}
