package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moagwa.auth.JwtService;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.organization.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.event.ApplicationEvents;

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class CategoryBackfillTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository notices;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerService crawler;
    @Autowired ApplicationEvents events;
    @MockBean PageFetcher fetcher;
    CrawlSource source;
    long scholarship, general, assigned, missing;
    final String url = "https://school.example/community/notice.do";

    @BeforeEach void setup() throws Exception {
        var org = orgs.save(new Organization("분류검증-"+UUID.randomUUID(),OrganizationType.DEPARTMENT));
        source = sources.save(new CrawlSource("category-"+UUID.randomUUID(),org,url));
        scholarship=save("101","장학금 신청",null);
        general=save("102","장학금 제목이지만 원문 일반",null);
        assigned=save("103","기존 분류 유지","WELFARE");
        missing=save("105","장학금 제목이지만 원문 분류 없음",null);
        when(fetcher.fetch(anyString())).thenAnswer(call -> Jsoup.parse(list(), call.<String>getArgument(0)));
    }
    long save(String external,String title,String category) {
        var n = new CrawledNotice(source,external,title,url+"?mode=view&articleNo="+external,false,Instant.now(),Instant.now());
        n.assignCategory(category);
        return notices.saveAndFlush(n).getId();
    }
    String list() { return "<table><tbody>"+row("101","장학")+row("102","일반")+row("103","장학")+row("104","행사")+row("105",null)+"</tbody></table>"; }
    String row(String id,String label) { return "<tr><td><a class='b-title' href='?mode=view&amp;articleNo="+id+"'>공지 "+id+"</a>"+(label==null?"":"<span class='b-cate'>"+label+"</span>")+"<span class='b-date'>2026.11.15</span></td></tr>"; }
    String path() { return "/api/admin/crawler/categories/backfill?sourceId="+source.getId(); }
    String operator() { return "Bearer "+jwt.issueAdmin(1,source.getOrganization().getId(),true); }
    String category(long id) { return jdbc.queryForObject("SELECT category FROM crawled_notices WHERE id=?",String.class,id); }

    @Test void dryRunShowsEvidenceAndDoesNotChangeOrCreateAnything() throws Exception {
        mvc.perform(post(path()).header("Authorization",operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true))
            .andExpect(jsonPath("$.wouldChange").value(1)).andExpect(jsonPath("$.updated").value(0))
            .andExpect(jsonPath("$.changes[0].boardLabel").value("장학"));
        assertThat(category(scholarship)).isNull();
        verify(fetcher,times(1)).fetch(url+"?article.offset=0&articleLimit=10");
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
    }

    @Test void appliesOnlyMissingCategoriesAndIsIdempotentAndVisibleThroughFeedFilter() throws Exception {
        mvc.perform(post(path()+"&dryRun=false").header("Authorization",operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        assertThat(category(scholarship)).isEqualTo("SCHOLARSHIP");
        assertThat(category(general)).isNull();
        assertThat(category(missing)).isNull();
        assertThat(category(assigned)).isEqualTo("WELFARE");
        assertThat(notices.findExistingExternalIds(source.getId(),java.util.List.of("104"))).isEmpty();
        mvc.perform(get("/api/feed?category=SCHOLARSHIP&organizationId="+source.getOrganization().getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(scholarship));
        mvc.perform(post(path()+"&dryRun=false").header("Authorization",operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(0));
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
    }

    @Test void regularCrawlClassifiesNewNoticesAndBackfillsExistingWithoutOverwriting() throws Exception {
        when(fetcher.fetch(anyString())).thenAnswer(call -> {
            String u=call.getArgument(0);
            return Jsoup.parse(u.equals(url)?list():"<div class='b-con-box'>본문</div>",u);
        });
        assertThat(crawler.crawlSource(source).status()).isEqualTo(com.moagwa.crawler.domain.CrawlStatus.SUCCESS);
        assertThat(category(scholarship)).isEqualTo("SCHOLARSHIP");
        assertThat(category(assigned)).isEqualTo("WELFARE");
        assertThat(category(general)).isNull();
        assertThat(category(missing)).isNull();
        assertThat(notices.findBySourceAndExternalId(source.getId(),"104").orElseThrow().getCategory()).isEqualTo("EVENT");
    }

    @Test void onlyOperatorCanBackfillAndInvalidBoundsDoNotFetch() throws Exception {
        mvc.perform(post(path())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).header("Authorization","Bearer "+jwt.issueStudent(1))).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Authorization","Bearer "+jwt.issueAdmin(1,1))).andExpect(status().isForbidden());
        for(String params:new String[]{"&offset=-1","&offset=100001","&pageSize=0","&pageSize=51"}) {
            mvc.perform(post(path()+params).header("Authorization",operator())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(fetcher);
    }

    @Test void missingOrDisabledSourcesAreRejectedWithoutNetworkRequests() throws Exception {
        mvc.perform(post("/api/admin/crawler/categories/backfill?sourceId="+Long.MAX_VALUE)
            .header("Authorization",operator())).andExpect(status().isNotFound());
        source.disable(); sources.saveAndFlush(source);
        mvc.perform(post(path()).header("Authorization",operator())).andExpect(status().isBadRequest());
        verifyNoInteractions(fetcher);
    }

    @Test void emptyOrChangedLayoutDoesNotEraseExistingData() throws Exception {
        when(fetcher.fetch(anyString())).thenReturn(Jsoup.parse("<p>게시판 점검 중</p>",url));
        mvc.perform(post(path()+"&dryRun=false").header("Authorization",operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.parsedRows").value(0))
            .andExpect(jsonPath("$.updated").value(0));
        assertThat(category(scholarship)).isNull();
        assertThat(category(assigned)).isEqualTo("WELFARE");
    }

    @Test void requestedPageIsBoundedAndFailuresDoNotModifyCategories() throws Exception {
        mvc.perform(post(path()+"&offset=20&pageSize=10").header("Authorization",operator())).andExpect(status().isOk());
        verify(fetcher).fetch(url+"?article.offset=20&articleLimit=10");
        when(fetcher.fetch(anyString())).thenThrow(new IOException("timeout"));
        mvc.perform(post(path()+"&dryRun=false").header("Authorization",operator())).andExpect(status().isBadGateway());
        assertThat(category(scholarship)).isNull();
    }
}
