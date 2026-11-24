package vn.gk.customer.accounts;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class GoogleIdentityTest {
 static final String CLIENT="123-test.apps.googleusercontent.com",NONCE="n".repeat(43);
 KeyPair keys() throws Exception {var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);return gen.generateKeyPair();}
 String token(KeyPair key,String issuer,String audience,String nonce,boolean verified,Instant expiry) throws Exception {
  var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),new JWTClaimsSet.Builder().issuer(issuer).subject("google-user-123").audience(audience).expirationTime(Date.from(expiry)).issueTime(new Date()).claim("nonce",nonce).claim("email","customer@gmail.com").claim("email_verified",verified).claim("name","Nguyễn Minh Anh").build());jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
 }
 @Test void verifiesSignatureAudienceIssuerExpiryNonceAndEmail() throws Exception {
  var key=keys();var identity=new GoogleIdentity(CLIENT,NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build());assertTrue(identity.enabled());
  var good=token(key,"https://accounts.google.com",CLIENT,NONCE,true,Instant.now().plusSeconds(300));assertEquals("google-user-123",identity.verify(good,NONCE).subject());
  for(var bad:List.of(token(key,"evil.example",CLIENT,NONCE,true,Instant.now().plusSeconds(300)),token(key,"accounts.google.com","wrong",NONCE,true,Instant.now().plusSeconds(300)),token(key,"accounts.google.com",CLIENT,"wrong",true,Instant.now().plusSeconds(300)),token(key,"accounts.google.com",CLIENT,NONCE,false,Instant.now().plusSeconds(300)),token(key,"accounts.google.com",CLIENT,NONCE,true,Instant.now().minusSeconds(90)),token(keys(),"accounts.google.com",CLIENT,NONCE,true,Instant.now().plusSeconds(300))))assertEquals(401,assertThrows(ResponseStatusException.class,()->identity.verify(bad,NONCE)).getStatusCode().value());
  assertThrows(ResponseStatusException.class,()->identity.verify(good,null));assertThrows(ResponseStatusException.class,()->identity.verify("not-a-jwt",NONCE));
 }
 @Test void missingClientIdDisablesGoogle() throws Exception {var key=keys();var identity=new GoogleIdentity("",NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build());assertFalse(identity.enabled());assertEquals(503,assertThrows(ResponseStatusException.class,()->identity.verify("token",NONCE)).getStatusCode().value());}
}
