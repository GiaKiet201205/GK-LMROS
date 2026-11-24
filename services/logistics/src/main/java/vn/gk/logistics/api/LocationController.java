package vn.gk.logistics.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Duration;
import java.util.*;

/** Address lookup only: no map SDK, tile server, or browser API key. */
@RestController
@RequestMapping("/api/locations")
public class LocationController {
    private record Cached(long expires,List<Map<String,Object>> results) {}
    private final Map<String,Cached> cache=new LinkedHashMap<>();
    private final RestTemplate rest;
    private final String baseUrl;
    private long nextRequest;
    public LocationController(RestTemplateBuilder builder,@Value("${GEOCODER_URL:https://photon.komoot.io}") String baseUrl) {
        this.rest=builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(7)).build();
        this.baseUrl=baseUrl.replaceAll("/+$","");
    }
    @GetMapping
    @SuppressWarnings("unchecked")
    public synchronized Map<String,Object> search(@RequestParam String q) {
        String query=q.trim().replaceAll("\\s+"," ");
        if(query.length()<3||query.length()>200) throw new IllegalArgumentException("Nhập địa chỉ từ 3 đến 200 ký tự");
        String key=query.toLowerCase(Locale.ROOT);
        long now=System.currentTimeMillis();
        var saved=cache.get(key);
        if(saved!=null&&saved.expires>now) return response(saved.results);
        if(now<nextRequest) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Vui lòng chờ một giây rồi tìm lại địa chỉ");
        nextRequest=now+1100;
        try {
            var uri=UriComponentsBuilder.fromUriString(baseUrl+"/api/").queryParam("q",query).queryParam("limit",10)
                .queryParam("lat",10.78).queryParam("lon",106.7).queryParam("bbox","102.1,8.0,110.0,23.5").build().encode().toUri();
            var headers=new HttpHeaders();headers.set(HttpHeaders.USER_AGENT,"GK-Logistics/1.0 address-confirmation");
            var body=rest.exchange(uri,HttpMethod.GET,new HttpEntity<>(headers),Map.class).getBody();
            var results=new ArrayList<Map<String,Object>>();
            if(body!=null&&body.get("features") instanceof List<?> features) for(var item:features) {
                var feature=(Map<String,Object>)item;
                var properties=(Map<String,Object>)feature.get("properties");
                var geometry=(Map<String,Object>)feature.get("geometry");
                if(properties==null||geometry==null||!"VN".equalsIgnoreCase(Objects.toString(properties.get("countrycode"),""))) continue;
                var coordinates=(List<Number>)geometry.get("coordinates");
                if(coordinates==null||coordinates.size()<2) continue;
                double lat=coordinates.get(1).doubleValue(),lng=coordinates.get(0).doubleValue();
                if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180) continue;
                var parts=new LinkedHashSet<String>();
                for(var field:List.of("name","street","housenumber","district","city","state","country")) {
                    String value=Objects.toString(properties.get(field),"").trim();if(!value.isEmpty()) parts.add(value);
                }
                if(parts.isEmpty()) continue;
                results.add(Map.of("label",String.join(", ",parts),"lat",lat,"lng",lng,"source","photon"));
                if(results.size()==5) break;
            }
            cache.put(key,new Cached(now+3600000,results));
            while(cache.size()>256) cache.remove(cache.keySet().iterator().next());
            return response(results);
        } catch(Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa tìm được địa chỉ lúc này. Bạn có thể dán tọa độ hoặc liên kết Google Maps đầy đủ để xác nhận.");
        }
    }
    private Map<String,Object> response(List<Map<String,Object>> results) {
        return Map.of("results",results,"attribution","© OpenStreetMap contributors · Photon");
    }
}
