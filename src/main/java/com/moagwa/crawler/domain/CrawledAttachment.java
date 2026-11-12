package com.moagwa.crawler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Embeddable
public class CrawledAttachment {
    @Column(name = "file_name", nullable = false, columnDefinition = "text")
    private String fileName;
    @Column(nullable = false, columnDefinition = "text")
    private String url;

    public CrawledAttachment(String fileName, String url) {
        this.fileName = fileName;
        this.url = url;
    }
}
