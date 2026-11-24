package vn.gk.logistics.assistant;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

@Service
public class AssistantKnowledge {
 public record Document(String id,String title,String path,String content,String kind,long version) {}
 private final JdbcTemplate jdbc;
 private final List<Document> builtins;
 public static final Set<String> PATHS=Set.of("/","/orders","/orders/new","/distribution","/live","/routing","/warehouses","/vehicles","/drivers","/maintenance","/reports","/pricing","/notifications","/settings");
 public AssistantKnowledge(JdbcTemplate jdbc) {
  this.jdbc=jdbc;
  try {
   var docs=new ArrayList<Document>();
   for(var r:new PathMatchingResourcePatternResolver().getResources("classpath*:assistant/*.txt")) {
    String text=r.getContentAsString(StandardCharsets.UTF_8);String[] lines=text.split("\n",3);
    docs.add(new Document("guide:"+r.getFilename(),lines[0].replaceFirst("^# +",""),lines[1].trim(),lines[2].trim(),"guide",1));
   }
   docs.sort(Comparator.comparing(Document::id));builtins=List.copyOf(docs);
  } catch(java.io.IOException e) {throw new IllegalStateException("Cannot load assistant guides",e);}
 }
 public List<Document> all() {
  var docs=new ArrayList<>(builtins);
  jdbc.queryForList("SELECT id,title,path,content,version FROM assistant_documents WHERE deleted_at IS NULL ORDER BY id DESC LIMIT 100").forEach(r->docs.add(new Document("custom:"+r.get("id"),(String)r.get("title"),(String)r.get("path"),(String)r.get("content"),"custom",((Number)r.get("version")).longValue())));
  return docs;
 }
 public static String fold(String text) {return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);}
 public List<Document> search(String query,String page) {
  var words=new HashSet<>(Arrays.asList(fold(query).split("[^a-z0-9]+")));
  words.removeAll(Set.of("","toi","la","va","cua","co","the","nao","tai","sao","gi","cho","voi","he","thong","giup","minh","duoc","khong","nay","mot","cac","nhung","ban"));
  return all().stream().map(d->{String title=fold(d.title()),body=fold(d.content());int score=0;for(String w:words)if(w.length()>1)score+=(title.contains(w)?5:0)+(body.contains(w)?1:0);if(score>0&&d.path().equals(page))score+=2;return Map.entry(d,score);}).filter(e->e.getValue()>0).sorted(Map.Entry.<Document,Integer>comparingByValue().reversed().thenComparing(e->e.getKey().id())).limit(4).map(e->{var d=e.getKey();return new Document(d.id(),d.title(),d.path(),excerpt(d.content(),words),d.kind(),d.version());}).toList();
 }
 static String excerpt(String content,Set<String> words) {
  if(content.length()<=4500)return content;
  var chunks=new ArrayList<Map.Entry<Integer,Integer>>();
  for(int from=0;from<content.length();from+=1500){String part=fold(content.substring(from,Math.min(from+1800,content.length())));int score=0;for(String w:words)if(w.length()>1&&part.contains(w))score+=w.length();chunks.add(Map.entry(from,score));}
  var starts=chunks.stream().sorted(Map.Entry.<Integer,Integer>comparingByValue().reversed()).limit(2).map(Map.Entry::getKey).sorted().toList();
  var result=new StringBuilder();for(int start:starts){if(!result.isEmpty())result.append("\n[…]\n");result.append(content,start,Math.min(start+1800,content.length()));}return result.toString();
 }
 public record Write(String title,String path,String content,Long version) {}
 private void validate(Write b){if(b==null||b.title()==null||b.title().trim().length()<3||b.title().trim().length()>120||b.content()==null||b.content().trim().length()<20||b.content().length()>12000||!PATHS.contains(b.path()))throw new IllegalArgumentException("Nhập tiêu đề 3–120 ký tự, nội dung 20–12.000 ký tự và chọn mục liên quan.");}
 @Transactional public Map<String,Object> save(Long id,Write b,long user) {
  validate(b);
  if(id==null){jdbc.execute("SELECT pg_advisory_xact_lock(7462021)");if(jdbc.queryForObject("SELECT count(*) FROM assistant_documents WHERE deleted_at IS NULL",Integer.class)>=100)throw new IllegalArgumentException("Tối đa 100 tài liệu bổ sung. Hãy cập nhật tài liệu đang có.");id=jdbc.queryForObject("INSERT INTO assistant_documents(title,path,content,created_by,updated_by) VALUES(?,?,?,?,?) RETURNING id",Long.class,b.title().trim(),b.path(),b.content().trim(),user,user);}
  else if(b.version()==null||jdbc.update("UPDATE assistant_documents SET title=?,path=?,content=?,updated_by=?,updated_at=now(),version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL",b.title().trim(),b.path(),b.content().trim(),user,id,b.version())!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Tài liệu đã thay đổi. Hãy tải lại trước khi sửa.");
  return Map.of("id",id);
 }
 public void delete(long id,long version,long user) {if(jdbc.update("UPDATE assistant_documents SET deleted_at=now(),updated_at=now(),updated_by=?,version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL",user,id,version)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Tài liệu đã thay đổi hoặc đã xóa. Hãy tải lại.");}
}
