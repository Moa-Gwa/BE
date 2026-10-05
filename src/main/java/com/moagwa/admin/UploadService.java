package com.moagwa.admin;

import com.moagwa.common.exception.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Stores admin-uploaded notice images on local disk. File type is decided by content, not by file name. */
@Service
public class UploadService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_FILES = 10;

    private final Path root;

    public UploadService(@Value("${moagwa.upload.dir}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    public List<String> store(List<MultipartFile> files) {
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw ApiException.badRequest("이미지는 1~" + MAX_FILES + "장까지 업로드할 수 있습니다.");
        }
        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.getSize() > MAX_BYTES) {
                throw ApiException.badRequest("이미지는 장당 5MB 이하여야 합니다.");
            }
            try {
                byte[] bytes = file.getBytes();
                String ext = detectExtension(bytes);
                if (ext == null) {
                    throw ApiException.badRequest("JPG, PNG, WEBP 이미지만 업로드할 수 있습니다.");
                }
                String name = UUID.randomUUID() + "." + ext;
                Files.write(root.resolve(name), bytes);
                urls.add("/uploads/" + name);
            } catch (IOException e) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "업로드에 실패했습니다.");
            }
        }
        return urls;
    }

    static String detectExtension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
