package com.moagwa.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notice_images")
@Getter
@NoArgsConstructor
public class NoticeImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id")
    private Notice notice;

    @Column(nullable = false)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    NoticeImage(Notice notice, String url, int sortOrder) {
        this.notice = notice;
        this.url = url;
        this.sortOrder = sortOrder;
    }
}
