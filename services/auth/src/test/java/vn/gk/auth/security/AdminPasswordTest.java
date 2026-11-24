package vn.gk.auth.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPasswordTest {
    private AuthService service(String password) {
        return new AuthService(mock(JdbcTemplate.class),mock(RestTemplate.class),"http://logistics","internal-test-token",password);
    }
    @Test void rejectsBlankAndPlaceholderPasswordsBeforeDatabaseWrites() {
        for(String password:new String[]{""," ","replace-with-a-strong-admin-password"})
            assertThrows(IllegalArgumentException.class,()->service(password));
    }
    private ApplicationContextRunner context() {
        var jdbc=mock(JdbcTemplate.class);var rest=mock(RestTemplate.class);
        when(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE username='admin'",Integer.class)).thenReturn(1);
        when(rest.exchange(eq("http://logistics/internal/drivers"),any(),any(),eq(List.class))).thenReturn(ResponseEntity.ok(List.of()));
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withPropertyValues("app.logistics-url=http://logistics","app.internal-token=internal-test-token")
            .withBean(JdbcTemplate.class,()->jdbc).withBean(RestTemplate.class,()->rest).withBean(AuthService.class);
    }
    @Test void absentEnvironmentPasswordPreventsStartup() {
        context().run(context->{
            assertNotNull(context.getStartupFailure());
            Throwable root=context.getStartupFailure();while(root.getCause()!=null)root=root.getCause();
            assertTrue(root.getMessage().contains("ADMIN_PASSWORD"));
        });
    }
    @Test void explicitPasswordAllowsStartupWithTheSameDependencies() {
        context().withPropertyValues("ADMIN_PASSWORD=test-only-admin-password").run(context->assertNull(context.getStartupFailure()));
    }
    @Test void configuredPasswordNeverOverwritesExistingAccounts() {
        var jdbc=mock(JdbcTemplate.class);var rest=mock(RestTemplate.class);
        when(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE username='admin'",Integer.class)).thenReturn(1);
        when(rest.exchange(eq("http://logistics/internal/drivers"),any(),any(),eq(List.class))).thenReturn(ResponseEntity.ok(List.of()));
        var service=new AuthService(jdbc,rest,"http://logistics","internal-test-token","test-only-admin-password");
        service.initialize();
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void newAdminStoresAPasswordHashRatherThanTheConfiguredPlaintext() {
        var jdbc=mock(JdbcTemplate.class);var rest=mock(RestTemplate.class);
        when(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE username='admin'",Integer.class)).thenReturn(0);
        when(rest.exchange(eq("http://logistics/internal/drivers"),any(),any(),eq(List.class))).thenReturn(ResponseEntity.ok(List.of()));
        new AuthService(jdbc,rest,"http://logistics","internal-test-token","test-only-admin-password").initialize();
        var insert=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")&&i.getArgument(0).toString().startsWith("INSERT INTO app_users")).findFirst().orElseThrow();
        String hash=insert.getArgument(1).toString();assertTrue(hash.contains(":"));assertFalse(hash.contains("test-only-admin-password"));
    }
}
