package vn.gk.logistics.security;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthFilterTest {
    private boolean request(String method,String path,String portal,Map<String,Object> user,JdbcTemplate jdbc,int expectedStatus,boolean csrf) throws Exception {
        var auth=mock(AuthService.class);when(auth.user(any())).thenReturn(user);
        var filter=new AuthFilter(auth,jdbc);var req=new MockHttpServletRequest(method,path);
        req.addHeader("X-GK-Portal",portal);if(csrf)req.addHeader("X-GK","1");
        var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);
        assertEquals(expectedStatus,res.getStatus(),method+" "+path);return chain.getRequest()!=null;
    }
    @Test void anonymousAndMismatchedPortalsAreRejected() throws Exception {
        assertFalse(request("GET","/api/orders","dispatcher",null,mock(JdbcTemplate.class),401,false));
        assertFalse(request("GET","/api/orders","dispatcher",Map.of("role","driver","driver_id",1),mock(JdbcTemplate.class),403,false));
        assertFalse(request("GET","/api/orders","customer",null,mock(JdbcTemplate.class),400,false));
    }
    @Test void unknownPathsAndMutationsAreDeniedForBothRoles() throws Exception {
        for(String role:new String[]{"dispatcher","driver"}) {
            var u=Map.<String,Object>of("role",role,"driver_id",7);
            assertFalse(request("GET","/api/new-feature",role,u,mock(JdbcTemplate.class),403,false));
            assertFalse(request("POST","/api/new-feature",role,u,mock(JdbcTemplate.class),403,true));
        }
        assertFalse(request("POST","/api/finance/expenses","dispatcher",Map.of("role","dispatcher"),mock(JdbcTemplate.class),403,false));
    }
    @Test void driverMustOwnTripStopAndVehicleBeforeControllerRuns() throws Exception {
        var user=Map.<String,Object>of("role","driver","driver_id",7,"vehicle_code","XE-001");
        for(String path:new String[]{"/api/driver/trips/12/start","/api/driver/trips/12/finish","/api/driver/stops/12/complete","/api/driver/stops/12/collect","/api/driver/stops/12/payer","/api/driver/stops/12/unpaid","/api/driver/stops/12/fail","/api/driver/stops/12/depart"}) {
            var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(12L),eq(7))).thenReturn(0);
            assertFalse(request("POST",path,"driver",user,jdbc,403,true));
            when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(12L),eq(7))).thenReturn(1);
            assertTrue(request("POST",path,"driver",user,jdbc,200,true));
        }
        assertFalse(request("GET","/api/driver/XE-002/today","driver",user,mock(JdbcTemplate.class),403,false));
        assertTrue(request("GET","/api/driver/XE-001/today","driver",user,mock(JdbcTemplate.class),200,false));
        var jdbc=mock(JdbcTemplate.class);
        assertFalse(request("POST","/api/driver/trips/999999999999999999999/start","driver",user,jdbc,403,true));verifyNoInteractions(jdbc);
    }
    @Test void ambiguousPathsCannotBypassTheApiPrefixCheck() throws Exception {
        for(String path:new String[]{"//api/orders","/%61pi/orders","/api/../internal/drivers","/api/orders;ignored"})
            assertFalse(request("GET",path,"dispatcher",Map.of("role","dispatcher"),mock(JdbcTemplate.class),403,false));
    }
    @Test void healthIsReadOnlyAndOwnershipFailuresAreSafe() throws Exception {
        assertTrue(request("GET","/api/health","dispatcher",null,mock(JdbcTemplate.class),200,false));
        assertFalse(request("POST","/api/health","dispatcher",null,mock(JdbcTemplate.class),403,false));
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L),eq(1))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection details"));
        assertFalse(request("POST","/api/driver/trips/1/start","driver",Map.of("role","driver","driver_id",1),jdbc,503,true));
    }
}
