package vn.gk.logistics.security;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;

class ApiAccessPolicyTest {
    @Test void allPublicControllerEndpointsHaveAnExplicitGrant() throws Exception {
        var scanner=new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));int covered=0;
        for(var bean:scanner.findCandidateComponents("vn.gk.logistics")) {
            Class<?> type=Class.forName(bean.getBeanClassName());
            var parent=AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
            String[] bases=parent==null||parent.path().length==0?new String[]{""}:parent.path();
            for(var method:type.getDeclaredMethods()) {
                var mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
                if(mapping==null)continue;
                String[] leaves=mapping.path().length==0?new String[]{""}:mapping.path();
                for(var base:bases)for(var leaf:leaves) {
                    String path=(base+leaf).replace("{vehicleCode}","XE-001").replace("{kind}","vehicles").replace("{action}","approve").replaceAll("\\{[^}]+}","1");
                    if(!path.startsWith("/api/")||path.equals("/api/health"))continue;
                    assertTrue(mapping.method().length>0,"Declare explicit verbs: "+method);
                    for(var verb:mapping.method()) {
                        assertTrue(ApiAccessPolicy.grant(verb.name(),path,"dispatcher").isPresent()||ApiAccessPolicy.grant(verb.name(),path,"driver").isPresent(),"Missing authorization: "+verb+" "+path);
                        covered++;
                    }
                }
            }
        }
        assertTrue(covered>80,"Controller scan must cover the whole API");
    }
    @Test void newRoutesUnknownActionsAndWrongVerbsDenyEvenAdmin() {
        for(String role:List.of("dispatcher","driver","customer","unknown"))
            for(String method:List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"))
                assertTrue(ApiAccessPolicy.grant(method,"/api/new-feature",role).isEmpty());
        for(String path:List.of("/api/finance/report/extra","/api/driver/stops/1/delete","/api/fleet/users","/api/orders/1;secret","/api/%66inance/report","/api//orders","/api/driver/stops/../1/complete"))
            assertTrue(ApiAccessPolicy.grant("POST",path,"dispatcher").isEmpty(),path);
        assertTrue(ApiAccessPolicy.grant("POST","/api/finance/report","dispatcher").isEmpty());
        assertTrue(ApiAccessPolicy.grant("DELETE","/api/orders/1","dispatcher").isEmpty());
        assertTrue(ApiAccessPolicy.grant("GET","/api/finance/report","driver").isEmpty());
        assertTrue(ApiAccessPolicy.grant("GET","/api/assistant/knowledge","driver").isEmpty());
        assertTrue(ApiAccessPolicy.grant("GET","/api/driver/today","dispatcher").isEmpty());
        assertTrue(ApiAccessPolicy.grant("HEAD","/api/workday","driver").isPresent());
    }
}
