package com.moagwa.admin;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.auth.JwtService;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 사진 업로드는 multipart 본문의 files 파트로 받는다(관리자 공지, 생활 글 공통) */
@SpringBootTest
@AutoConfigureMockMvc
class UploadApiTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired OrganizationRepository organizations;
    @Autowired AdminRepository admins;
    @Autowired UserRepository users;

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("files", name, "image/png", PNG);
    }

    @Test
    void 관리자는_여러_장을_files_파트로_올린다() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Organization org = organizations.save(new Organization("업로드학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        Admin admin = admins.save(new Admin("up-" + tag, encoder.encode("pw-1234"), "관리자", org));

        mvc.perform(multipart("/api/admin/uploads/images").file(png("a.png")).file(png("b.png"))
                        .header("Authorization", "Bearer " + jwt.issueAdmin(admin.getId(), org.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(2))
                .andExpect(jsonPath("$.urls[0]").value(matchesPattern("^/uploads/[0-9a-f-]+\\.png$")));
    }

    @Test
    void 재학생은_생활_사진을_올리고_파일이_없으면_400() throws Exception {
        User student = users.save(new User("DEV", "up-" + UUID.randomUUID(), "업로드학생"));
        String token = "Bearer " + jwt.issueStudent(student.getId(), true);

        mvc.perform(multipart("/api/market/uploads/images").file(png("item.png")).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(1));
        mvc.perform(multipart("/api/market/uploads/images").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }
}
