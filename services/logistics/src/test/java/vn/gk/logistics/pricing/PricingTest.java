package vn.gk.logistics.pricing;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class PricingTest {
 @Test void roundsMetresHalfUpWithoutFloatingPointMoney(){assertEquals(20,ShippingPricingService.billableKm(20400));assertEquals(21,ShippingPricingService.billableKm(20500));assertEquals(20,ShippingPricingService.billableKm(20499));assertEquals(0,ShippingPricingService.billableKm(499));assertEquals(1,ShippingPricingService.billableKm(500));assertEquals(40000L,ShippingPricingService.billableKm(20400)*2000);assertThrows(IllegalArgumentException.class,()->ShippingPricingService.billableKm(-1));}
 @Test void usesGoogleDrivingDistanceAndDoesNotHideProviderFailure(){var customizer=new MockServerRestTemplateCustomizer();var roads=new RoadDistance(new RestTemplateBuilder(customizer),"test-key");var server=customizer.getServer();server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes")).andExpect(header("X-Goog-Api-Key","test-key")).andExpect(header("X-Goog-FieldMask","routes.distanceMeters")).andRespond(withSuccess("{\"routes\":[{\"distanceMeters\":20400}]}",MediaType.APPLICATION_JSON));var result=roads.between(10.7,106.6,10.8,106.7);assertEquals(20400,result.metres());assertEquals("google",result.source());server.verify();server.reset();server.expect(anything()).andRespond(withServerError());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roads.between(10.7,106.6,10.8,106.7));server.verify();}
 @Test void missingKeyIsClearlyAnEstimate(){var result=new RoadDistance(new RestTemplateBuilder(),"").between(10.7,106.6,10.8,106.7);assertEquals("estimate",result.source());assertTrue(result.metres()>0);}
}
