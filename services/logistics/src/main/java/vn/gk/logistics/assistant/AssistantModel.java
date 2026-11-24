package vn.gk.logistics.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

@Service
public class AssistantModel {
 private final RestTemplate rest;
 private final String key,model,provider;
 private final boolean enabled;
 public AssistantModel(RestTemplateBuilder builder,
   @Value("${GK_ASSISTANT_ENABLED:false}") boolean enabled,
   @Value("${GK_ASSISTANT_PROVIDER:openai}") String provider,
   @Value("${OPENAI_API_KEY:}") String openaiKey,
   @Value("${GK_ASSISTANT_MODEL:gpt-4.1-mini}") String openaiModel,
   @Value("${GEMINI_API_KEY:}") String geminiKey,
   @Value("${GEMINI_MODEL:gemini-3.5-flash-lite}") String geminiModel) {
  this.rest=builder.setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofSeconds(45)).build();
  this.enabled=enabled;this.provider=provider.trim().toLowerCase(Locale.ROOT);
  this.key=(this.provider.equals("gemini")?geminiKey:openaiKey).trim();
  this.model=(this.provider.equals("gemini")?geminiModel:openaiModel).trim();
 }
 public boolean ready(){return enabled&&Set.of("openai","gemini").contains(provider)&&!key.isBlank()&&model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}");}
 public String answer(String instructions,List<Map<String,String>> input) {
  if(!ready())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Trợ lý AI chưa được cấu hình. Kiểm tra nhà cung cấp, API key và tên model.");
  try {
   String answer=provider.equals("gemini")?gemini(instructions,input):openai(instructions,input);
   if(answer.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"AI chưa trả được câu trả lời. Hãy rút gọn câu hỏi và thử lại.");
   return answer.trim();
  } catch(HttpClientErrorException e) {
   if(e.getStatusCode().value()==429)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Dịch vụ AI đã đạt giới hạn sử dụng hoặc ngân sách. Hãy kiểm tra cấu hình hoặc thử lại sau.");
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Không kết nối được AI. Kiểm tra nhà cung cấp, quyền API key và model trong cấu hình backend.");
  } catch(RestClientException e) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Dịch vụ AI tạm thời không phản hồi. Hãy thử lại sau.");}
 }
 private String openai(String instructions,List<Map<String,String>> input) {
  var headers=new HttpHeaders();headers.setBearerAuth(key);headers.setContentType(MediaType.APPLICATION_JSON);
  JsonNode response=rest.postForObject("https://api.openai.com/v1/responses",new HttpEntity<>(Map.of("model",model,"instructions",instructions,"input",input,"max_output_tokens",1400,"store",false),headers),JsonNode.class);
  var text=new StringBuilder();if(response!=null)for(JsonNode item:response.path("output"))if("message".equals(item.path("type").asText()))for(JsonNode c:item.path("content"))if("output_text".equals(c.path("type").asText()))text.append(c.path("text").asText()).append("\n");
  return text.toString();
 }
 private String gemini(String instructions,List<Map<String,String>> input) {
  var headers=new HttpHeaders();headers.set("x-goog-api-key",key);headers.setContentType(MediaType.APPLICATION_JSON);
  var contents=new ArrayList<Map<String,Object>>();
  for(var message:input) {
   String role="assistant".equals(message.get("role"))?"model":"user";
   // Combine consecutive user turns (retrieval evidence + question) for Gemini chat.
   if(contents.isEmpty()||!role.equals(contents.get(contents.size()-1).get("role")))contents.add(Map.of("role",role,"parts",new ArrayList<Map<String,String>>()));
   @SuppressWarnings("unchecked") var parts=(List<Map<String,String>>)contents.get(contents.size()-1).get("parts");
   parts.add(Map.of("text",message.get("content")));
  }
  var body=Map.of("systemInstruction",Map.of("parts",List.of(Map.of("text",instructions))),"contents",contents,"generationConfig",Map.of("maxOutputTokens",4096,"candidateCount",1));
  JsonNode response=rest.postForObject("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent",new HttpEntity<>(body,headers),JsonNode.class);
  var text=new StringBuilder();if(response!=null){var candidate=response.path("candidates").path(0);
   if("MAX_TOKENS".equals(candidate.path("finishReason").asText()))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Câu trả lời AI vượt giới hạn độ dài. Hãy chia nhỏ câu hỏi và thử lại.");
   for(JsonNode part:candidate.path("content").path("parts"))if(!part.path("thought").asBoolean(false)&&part.path("text").isTextual())text.append(part.path("text").asText()).append("\n");
  }
  return text.toString();
 }
}
