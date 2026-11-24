package vn.gk.customer.accounts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;

@Service
public class GoogleIdentity {
 public record Profile(String subject,String email,String name) {}
 private final String clientId;private final NimbusJwtDecoder decoder;
 @Autowired public GoogleIdentity(RestTemplateBuilder builder,@Value("${CUSTOMER_GOOGLE_CLIENT_ID:}") String clientId) {
  this(clientId,NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(builder.setConnectTimeout(Duration.ofSeconds(4)).setReadTimeout(Duration.ofSeconds(8)).build()).build());
 }
 GoogleIdentity(String clientId,NimbusJwtDecoder decoder) {
  this.clientId=clientId.trim();this.decoder=decoder;
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),jwt->{
   boolean valid=jwt.getClaimAsString("iss")!=null&&jwt.getAudience()!=null&&Set.of("accounts.google.com","https://accounts.google.com").contains(jwt.getClaimAsString("iss"))&&jwt.getAudience().contains(this.clientId)&&jwt.getExpiresAt()!=null;
   return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
  }));
 }
 public String clientId(){return clientId;}
 public boolean enabled(){return clientId.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com");}
 public Profile verify(String credential,String nonce) {
  if(!enabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Đăng nhập Google chưa được bật.");
  if(credential==null||credential.length()>16384||nonce==null||!nonce.matches("[A-Za-z0-9_-]{43}"))throw denied();
  try {
   Jwt jwt=decoder.decode(credential);String actual=jwt.getClaimAsString("nonce"),subject=jwt.getSubject(),email=jwt.getClaimAsString("email");
   if(actual==null||!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))||subject==null||subject.isBlank()||subject.length()>255||!Boolean.TRUE.equals(jwt.getClaims().get("email_verified"))||email==null||email.length()>320||!email.contains("@"))throw denied();
   String name=jwt.getClaimAsString("name");return new Profile(subject,email,name==null?"":name);
  } catch(BadJwtException|IllegalArgumentException e){throw denied();}
  catch(JwtException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa xác minh được Google. Hãy thử lại sau.");}
 }
 private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phiên Google không hợp lệ hoặc đã hết hạn. Hãy chọn lại tài khoản Google.");}
}
