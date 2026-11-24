package vn.gk.logistics.pricing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

@Service
public class RoadDistance {
 private final RestTemplate rest;private final String key;
 public RoadDistance(RestTemplateBuilder builder,@Value("${GOOGLE_ROUTES_API_KEY:}") String key){this.key=key;rest=builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(10)).build();}
 public record Result(long metres,String source) {}
 private Map<String,Object> point(double lat,double lng){return Map.of("location",Map.of("latLng",Map.of("latitude",lat,"longitude",lng)));}
 @SuppressWarnings("unchecked")
 public Result between(double a,double b,double c,double d){
  if(key.isBlank()) {
   double p=Math.toRadians(a),q=Math.toRadians(c),x=Math.pow(Math.sin((q-p)/2),2)+Math.cos(p)*Math.cos(q)*Math.pow(Math.sin(Math.toRadians(d-b)/2),2);
   return new Result(Math.round(12742000*Math.asin(Math.sqrt(Math.min(1,x)))*1.35),"estimate");
  }
  var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Goog-Api-Key",key);headers.set("X-Goog-FieldMask","routes.distanceMeters");
  try {
   var payload=Map.of("origin",point(a,b),"destination",point(c,d),"travelMode","DRIVE","routingPreference","TRAFFIC_UNAWARE","computeAlternativeRoutes",false);
   var response=rest.postForObject("https://routes.googleapis.com/directions/v2:computeRoutes",new HttpEntity<>(payload,headers),Map.class);
   if(response==null||!(response.get("routes") instanceof List<?> routes)||routes.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Google Maps không tìm được đường đi giữa hai địa chỉ.");
   var route=(Map<?,?>)routes.get(0);if(!(route.get("distanceMeters") instanceof Number n)||n.longValue()<0)throw new IllegalStateException();
   return new Result(n.longValue(),"google");
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa lấy được quãng đường Google Maps. Vui lòng thử lại; GK chưa xác nhận cước.");}
 }
}
