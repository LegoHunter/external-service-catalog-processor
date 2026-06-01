package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.imaging.bitly.config.BitlyProperties;
import io.legohunter.imaging.bitly.impl.BitlinksService;
import io.legohunter.imaging.bitly.model.bitly.Bitlink;
import io.legohunter.imaging.bitly.model.bitly.BitlinksPage;
import io.legohunter.imaging.bitly.model.bitly.Pagination;
import io.legohunter.imaging.bitly.model.bitly.ShortenRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageHostingShortUrlServiceTest {
    private BitlinksService bitlinksService;
    private BitlyProperties bitlyProperties;
    private ImageHostingShortUrlService service;

    @BeforeEach
    void setUp() {
        bitlinksService = mock(BitlinksService.class);
        bitlyProperties = new BitlyProperties();
        bitlyProperties.setAccessToken("token");
        bitlyProperties.setGroupGuid("group-1");
        service = new ImageHostingShortUrlService(
                Optional.of(bitlinksService),
                bitlyProperties
        );
    }

    @Test
    void recoverExistingShortUrlPagesThroughBitlyAndMatchesByAlbumId() {
        when(bitlinksService.listBitlinks("group-1", 100, null, "both"))
                .thenReturn(page(List.of(), "cursor-2"));
        when(bitlinksService.listBitlinks("group-1", 100, "cursor-2", "both"))
                .thenReturn(page(List.of(bitlink(
                        "https://www.flickr.com/photos/example/sets/721577100/",
                        "https://bit.ly/album-100"
                )), null));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(
                "https://www.flickr.com/photos/example/albums/721577100"
        );

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl
                )
                .containsExactly(ImageHostingShortUrlStatus.RECOVERED_EXISTING, "https://bit.ly/album-100");
        verify(bitlinksService).listBitlinks("group-1", 100, null, "both");
        verify(bitlinksService).listBitlinks("group-1", 100, "cursor-2", "both");
    }

    @Test
    void recoverExistingShortUrlReportsLookupMissWhenNoBitlyLinkMatches() {
        when(bitlinksService.listBitlinks("group-1", 100, null, "both"))
                .thenReturn(page(List.of(bitlink(
                        "https://www.flickr.com/photos/example/albums/other",
                        "https://bit.ly/other"
                )), null));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(
                "https://www.flickr.com/photos/example/albums/721577100"
        );

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.LOOKUP_MISS);
        assertThat(result.getShortUrl()).isNull();
    }

    @Test
    void recoverExistingShortUrlIsDisabledWhenBitlyReadConfigIsMissing() {
        bitlyProperties.setGroupGuid(null);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(
                "https://www.flickr.com/photos/example/albums/721577100"
        );

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void generateShortUrlCallsBitlyShortenWithConfiguredGroup() {
        Bitlink bitlink = new Bitlink();
        bitlink.setLink("https://bit.ly/new-album");
        when(bitlinksService.shorten(any())).thenReturn(bitlink);

        ImageHostingShortUrlResult result = service.generateShortUrl(
                "https://www.flickr.com/photos/example/albums/721577200"
        );

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl
                )
                .containsExactly(ImageHostingShortUrlStatus.GENERATED_NEW, "https://bit.ly/new-album");
        ArgumentCaptor<ShortenRequest> requestCaptor = ArgumentCaptor.forClass(ShortenRequest.class);
        verify(bitlinksService).shorten(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(ShortenRequest::getGroupGuid, ShortenRequest::getLongUrl)
                .containsExactly("group-1", "https://www.flickr.com/photos/example/albums/721577200");
    }

    private static BitlinksPage page(List<Bitlink> links, String searchAfter) {
        Pagination pagination = new Pagination();
        pagination.setSearchAfter(searchAfter);
        BitlinksPage page = new BitlinksPage();
        page.setLinks(links);
        page.setPagination(pagination);
        return page;
    }

    private static Bitlink bitlink(String longUrl, String link) {
        Bitlink bitlink = new Bitlink();
        bitlink.setLongUrl(longUrl);
        bitlink.setLink(link);
        return bitlink;
    }

}
