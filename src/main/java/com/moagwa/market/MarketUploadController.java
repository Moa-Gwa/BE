package com.moagwa.market;

import com.moagwa.admin.UploadService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 물품·모집 글 사진 업로드. 관리자 공지 이미지와 같은 저장소와 검사를 쓴다. */
@RestController
@RequestMapping("/api/market/uploads")
@RequiredArgsConstructor
class MarketUploadController {

    private final UploadService uploads;

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, List<String>> upload(@RequestParam("files") List<MultipartFile> files) {
        return Map.of("urls", uploads.store(files));
    }
}
