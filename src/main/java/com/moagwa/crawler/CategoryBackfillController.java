package com.moagwa.crawler;

import com.moagwa.common.exception.ApiException;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.crawler.parser.BoardParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** 과거 목록 한 페이지만 확인한다. 상세 본문·첨부파일 재수집과 새 공지/알림 생성은 하지 않는다. */
@RestController
@RequestMapping("/api/admin/crawler/categories")
@RequiredArgsConstructor
class CategoryBackfillController {
    private final CrawlSourceRepository sources;
    private final CrawledNoticeRepository notices;
    private final PageFetcher fetcher;
    private final BoardParser parser;
    private final CrawlPersistence persistence;

    record Change(long noticeId, String externalId, String title, String boardLabel, String category) {}
    record Result(long sourceId, int offset, int pageSize, boolean dryRun, int parsedRows,
                  int matchedRows, int wouldChange, int updated, Map<String, Long> unmappedLabels,
                  java.util.List<Change> changes) {}

    @PostMapping("/backfill")
    Result backfill(@RequestParam long sourceId,
                    @RequestParam(defaultValue = "0") int offset,
                    @RequestParam(defaultValue = "10") int pageSize,
                    @RequestParam(defaultValue = "true") boolean dryRun) {
        if (sourceId <= 0 || offset < 0 || offset > 100000 || pageSize < 1 || pageSize > 50) {
            throw ApiException.badRequest("sourceId는 양수, offset은 0~100000, pageSize는 1~50이어야 합니다.");
        }
        var source = sources.findById(sourceId).orElseThrow(() -> ApiException.notFound("수집처가 없습니다."));
        if (!source.isEnabled()) throw ApiException.badRequest("활성 학교 게시판만 보완할 수 있습니다.");
        String url = UriComponentsBuilder.fromUriString(source.getListUrl())
                .replaceQueryParam("article.offset", offset).replaceQueryParam("articleLimit", pageSize)
                .build().toUriString();
        final java.util.List<com.moagwa.crawler.parser.ListItem> items;
        try {
            items = parser.parseList(fetcher.fetch(url), source);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "원문 목록을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (items.isEmpty()) return new Result(sourceId, offset, pageSize, dryRun, 0, 0, 0, 0, Map.of(), java.util.List.of());
        var existing = notices.findBySourceIdAndExternalIdIn(sourceId, items.stream().map(i -> i.externalId()).toList())
                .stream().collect(Collectors.toMap(n -> n.getExternalId(), Function.identity()));
        var changes = new ArrayList<Change>();
        int updated = 0;
        for (var item : items) {
            var notice = existing.get(item.externalId());
            String code = BoardCategoryMapper.toCode(item.category());
            if (notice == null || notice.getCategory() != null || code == null) continue;
            changes.add(new Change(notice.getId(), item.externalId(), notice.getTitle(), item.category(), code));
            // 기존 분류가 동시에 생겼더라도 UPDATE ... category IS NULL 조건으로 보호한다.
            if (!dryRun) updated += persistence.fillMissingCategory(sourceId, java.util.List.of(item.externalId()), code);
        }
        var unmapped = items.stream().filter(i -> BoardCategoryMapper.toCode(i.category()) == null)
                .collect(Collectors.groupingBy(i -> i.category() == null ? "(분류 없음)" : i.category(), Collectors.counting()));
        return new Result(sourceId, offset, pageSize, dryRun, items.size(), existing.size(), changes.size(), updated, unmapped, changes);
    }
}
