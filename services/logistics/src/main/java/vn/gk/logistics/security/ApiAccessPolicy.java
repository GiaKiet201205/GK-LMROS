package vn.gk.logistics.security;

import java.util.*;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Explicit method/path/role grants. A missing rule always denies every role. */
public final class ApiAccessPolicy {
    public enum Ownership { NONE, TRIP, STOP, VEHICLE }
    public record Grant(Ownership ownership, Map<String,String> variables) {}
    private record Rule(String method, PathPattern path, Set<String> roles, Ownership ownership) {}
    private static final Set<String> ADMIN=Set.of("dispatcher"), DRIVER=Set.of("driver"), BOTH=Set.of("dispatcher","driver");
    private static final List<Rule> RULES=rules();
    private static List<Rule> rules() {
        var rules=new ArrayList<Rule>();
        add(rules,"GET",ADMIN,
            "/api/dashboard","/api/alerts","/api/warehouses","/api/orders","/api/orders/{id:[0-9]+}",
            "/api/vehicles","/api/drivers","/api/live","/api/trips","/api/trips/{id:[0-9]+}/stops",
            "/api/preferences","/api/notifications","/api/locations","/api/journeys","/api/routing",
            "/api/distribution/resources","/api/maintenance","/api/vehicle-costs",
            "/api/pricing/tariffs","/api/payments/overview",
            "/api/finance/resources","/api/finance/attendance","/api/finance/payrolls","/api/finance/report",
            "/api/finance/entries","/api/finance/unrecognized","/api/finance/maintenance","/api/finance/salaries",
            "/api/assistant/status","/api/assistant/knowledge");
        add(rules,"POST",ADMIN,
            "/api/orders","/api/orders/reschedule-batch","/api/orders/{id:[0-9]+}/requeue",
            "/api/orders/{id:[0-9]+}/reschedule","/api/orders/{id:[0-9]+}/fare",
            "/api/notifications/read","/api/notifications/{id:[0-9]+}/unread","/api/optimize",
            "/api/distribution/preview","/api/distribution/{id:[0-9]+}/confirm","/api/distribution/trips/{id:[0-9]+}/release",
            "/api/fleet/{kind:warehouses|vehicles|drivers}","/api/fleet/{kind:warehouses|vehicles|drivers}/{id:[0-9]+}/restore",
            "/api/maintenance/{id:[0-9]+}/approve","/api/maintenance/{id:[0-9]+}/reject",
            "/api/vehicle-costs","/api/vehicle-costs/{id:[0-9]+}/{action:approve|reject|cancel}",
            "/api/pricing/quote","/api/pricing/tariffs","/api/payments/handovers/{id:[0-9]+}/{action:approve|reject}",
            "/api/payments/orders/{id:[0-9]+}/refund","/api/finance/expenses","/api/finance/entries/{id:[0-9]+}/reverse",
            "/api/finance/revenue/{id:[0-9]+}","/api/finance/payrolls","/api/finance/payrolls/{id:[0-9]+}/approve",
            "/api/finance/payrolls/{id:[0-9]+}/cancel","/api/finance/maintenance/{id:[0-9]+}/cost",
            "/api/finance/salaries","/api/assistant/knowledge","/api/assistant/chat");
        add(rules,"PUT",ADMIN,"/api/preferences","/api/fleet/{kind:warehouses|vehicles|drivers}/{id:[0-9]+}","/api/assistant/knowledge/{id:[0-9]+}");
        add(rules,"DELETE",ADMIN,"/api/fleet/{kind:warehouses|vehicles|drivers}/{id:[0-9]+}","/api/assistant/knowledge/{id:[0-9]+}");
        add(rules,"GET",BOTH,"/api/workday");
        add(rules,"POST",ADMIN,"/api/vehicles/{id:[0-9]+}/availability");
        add(rules,"GET",ADMIN,"/api/work-schedules");
        add(rules,"GET",DRIVER,"/api/driver/today","/api/driver/schedule","/api/driver/notifications",
            "/api/driver/maintenance","/api/driver/vehicle-costs","/api/driver/cash","/api/finance/mine");
        add(rules,"POST",DRIVER,"/api/driver/schedule","/api/driver/notifications/read","/api/driver/gps",
            "/api/driver/vehicle-costs","/api/driver/cash/handovers","/api/driver/maintenance",
            "/api/driver/maintenance/{id:[0-9]+}/{action:estimate|complete}",
            "/api/maintenance","/api/maintenance/{id:[0-9]+}/{action:estimate|complete}");
        rules.add(rule("GET",DRIVER,Ownership.VEHICLE,"/api/driver/{vehicleCode}/today"));
        rules.add(rule("POST",DRIVER,Ownership.TRIP,"/api/driver/trips/{id:[0-9]+}/{action:start|finish}"));
        rules.add(rule("POST",DRIVER,Ownership.STOP,"/api/driver/stops/{id:[0-9]+}/{action:depart|complete|fail|collect|payer|unpaid}"));
        return List.copyOf(rules);
    }
    private static Rule rule(String method,Set<String> roles,Ownership ownership,String path) {
        return new Rule(method,PathPatternParser.defaultInstance.parse(path),roles,ownership);
    }
    private static void add(List<Rule> rules,String method,Set<String> roles,String...paths) {
        for(String path:paths)rules.add(rule(method,roles,Ownership.NONE,path));
    }
    public static boolean canonicalPath(String path) {
        return path.startsWith("/")&&!path.contains("%")&&!path.contains(";")&&!path.contains("\\")&&!path.contains("//")&&!path.contains("/./")&&!path.contains("/../");
    }
    public static Optional<Grant> grant(String method,String path,String role) {
        if(!canonicalPath(path))return Optional.empty();
        String verb="HEAD".equals(method)?"GET":method;
        var container=PathContainer.parsePath(path);
        for(var rule:RULES) {
            if(!rule.method.equals(verb)||!rule.roles.contains(role))continue;
            var match=rule.path.matchAndExtract(container);
            if(match!=null)return Optional.of(new Grant(rule.ownership,match.getUriVariables()));
        }
        return Optional.empty();
    }
    private ApiAccessPolicy() {}
}
