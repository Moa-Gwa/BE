package com.moagwa.admin;

import com.moagwa.common.exception.ApiException;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
class AdminApplicationController {
 private final NamedParameterJdbcTemplate jdbc; private final OrganizationRepository organizations;
 private final AdminRepository admins; private final PasswordEncoder encoder; private final ObjectProvider<JavaMailSender> mail;
 @Value("${moagwa.mail.from:noreply@moagwa.local}") String from;
 @Value("${moagwa.frontend-url:https://localhost:3000}") String frontend;
 private static final SecureRandom RANDOM=new SecureRandom();
 record Form(@NotBlank @Size(max=100) String organizationName,@NotBlank @Size(max=100) String university,
  @NotBlank @Size(max=100) String department,@NotBlank @Size(max=50) String contactName,
  @NotBlank @Email @Size(max=200) String email,@NotBlank @Size(max=40) String phone,
  @NotBlank @Size(max=500) String channelUrl,@NotBlank @Size(max=3000) String reason){}
 record Decision(@Size(max=500) String reason){}
 record Password(@NotBlank @Size(min=10,max=100) String password){}
 @PostMapping("/admin-applications") @ResponseStatus(HttpStatus.CREATED)
 Map<String,Object> submit(@Valid @RequestBody Form f){
  Long id=jdbc.queryForObject("INSERT INTO admin_applications(organization_name,university,department,contact_name,email,phone,channel_url,reason) VALUES(:org,:uni,:dept,:name,:email,:phone,:channel,:reason) RETURNING id",params(f),Long.class);
  return Map.of("id",id,"status","PENDING");
 }
 @GetMapping("/admin/applications") List<Map<String,Object>> list(@RequestParam(defaultValue="PENDING") String status){
  return jdbc.queryForList("SELECT id,organization_name AS \"organizationName\",university,department,contact_name AS \"contactName\",email,phone,channel_url AS \"channelUrl\",reason,status,rejection_reason AS \"rejectionReason\",created_at AS \"createdAt\" FROM admin_applications WHERE status=:status ORDER BY created_at",Map.of("status",status));
 }
 @PostMapping("/admin/applications/{id}/approve") @Transactional Map<String,Object> approve(@PathVariable long id, Authentication auth){
  Map<String,Object> a=jdbc.queryForMap("SELECT * FROM admin_applications WHERE id=:id FOR UPDATE",Map.of("id",id));
  if(!"PENDING".equals(a.get("status"))) throw ApiException.badRequest("이미 처리된 신청입니다.");
  String email=((String)a.get("email")).trim().toLowerCase();
  if(admins.findByLoginId(email).isPresent()) throw ApiException.badRequest("해당 메일로 이미 관리자 계정이 있습니다.");
  String orgName=(String)a.get("organization_name");
  if(organizations.findByName(orgName).isPresent()) throw ApiException.badRequest("같은 이름의 조직이 이미 있습니다.");
  Organization org=new Organization(orgName,OrganizationType.STUDENT_GOVERNMENT); org.markVerified(); org.updateInfo("대학: "+a.get("university")+" · "+a.get("department"),null,(String)a.get("channel_url")); organizations.save(org);
  Admin admin=admins.save(new Admin(email,encoder.encode(java.util.UUID.randomUUID().toString()),(String)a.get("contact_name"),org));
  String token=token(), hash=hash(token); Instant expires=Instant.now().plusSeconds(3600);
  jdbc.update("UPDATE admin_applications SET status='APPROVED',organization_id=:org,admin_id=:admin,setup_token_hash=:hash,setup_expires_at=:expires,resolved_at=now() WHERE id=:id",new MapSqlParameterSource().addValue("org",org.getId()).addValue("admin",admin.getId()).addValue("hash",hash).addValue("expires",java.sql.Timestamp.from(expires)).addValue("id",id));
  String link=frontend+"/admin-setup?token="+URLEncoder.encode(token,StandardCharsets.UTF_8);
  JavaMailSender sender=mail.getIfAvailable();
  if(sender!=null){SimpleMailMessage m=new SimpleMailMessage();m.setFrom(from);m.setTo(email);m.setSubject("[모아과] 관리자 계정 설정");m.setText("비밀번호 설정 링크 (1시간 유효):\n"+link);sender.send(m);}
  return Map.of("setupLink",sender==null?link:"메일로 발송했습니다.");
 }
 @PostMapping("/admin/applications/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT)
 void reject(@PathVariable long id,@RequestBody(required=false) Decision d){int n=jdbc.update("UPDATE admin_applications SET status='REJECTED',rejection_reason=:reason,resolved_at=now() WHERE id=:id AND status='PENDING'",new MapSqlParameterSource().addValue("id",id).addValue("reason",d==null?null:d.reason()));if(n==0)throw ApiException.notFound("대기 중인 신청이 없습니다.");}
 @PostMapping("/admin/auth/setup/validate") Map<String,Object> validate(@RequestBody Map<String,String> b){return jdbc.query("SELECT a.email,a.setup_expires_at FROM admin_applications a WHERE a.setup_token_hash=:hash AND a.status='APPROVED'",new MapSqlParameterSource("hash",hash(b.getOrDefault("token",""))),(r,i)->Map.<String,Object>of("email",r.getString("email"),"valid",r.getTimestamp("setup_expires_at").toInstant().isAfter(Instant.now()))).stream().findFirst().orElseThrow(()->ApiException.notFound("유효하지 않은 설정 링크입니다."));}
 @PostMapping("/admin/auth/setup") @ResponseStatus(HttpStatus.NO_CONTENT)
 @Transactional void setup(@RequestBody Map<String,String> b){String h=hash(b.getOrDefault("token",""));Map<String,Object> a=jdbc.query("SELECT admin_id FROM admin_applications WHERE setup_token_hash=:hash AND status='APPROVED' AND setup_expires_at>now() FOR UPDATE",new MapSqlParameterSource("hash",h),(r,i)->Map.<String,Object>of("id",r.getLong("admin_id"))).stream().findFirst().orElseThrow(()->ApiException.badRequest("설정 링크가 만료되었거나 이미 사용되었습니다."));String pw=b.getOrDefault("password","");if(pw.length()<10||pw.length()>100)throw ApiException.badRequest("비밀번호는 10자 이상 입력해 주세요.");admins.findById((Long)a.get("id")).orElseThrow().activate(encoder.encode(pw));jdbc.update("UPDATE admin_applications SET setup_token_hash=NULL,setup_expires_at=NULL WHERE admin_id=:id",Map.of("id",a.get("id")));}
 private static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return HexFormat.of().formatHex(b);} private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static MapSqlParameterSource params(Form f){return new MapSqlParameterSource().addValue("org",f.organizationName()).addValue("uni",f.university()).addValue("dept",f.department()).addValue("name",f.contactName()).addValue("email",f.email()).addValue("phone",f.phone()).addValue("channel",f.channelUrl()).addValue("reason",f.reason());}
}
