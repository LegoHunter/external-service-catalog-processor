package io.legohunter.ingress.source.photo.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class MetadataExtractorService {

    // =========================
    // Public API
    // =========================

    public Map<String, String> extractKeywords(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes)) {

            Metadata metadata = ImageMetadataReader.readMetadata(is);
            Map<String, String> result = new HashMap<>();

            for (Directory dir : metadata.getDirectories()) {
                for (Tag tag : dir.getTags()) {

                    if (isKeywordTag(tag.getTagName())) {
                        String raw = tag.getDescription();
                        result.putAll(parseKeywordString(raw));
                    }
                }
            }

            log.debug("metadata.keywords.extracted count={}", result.size());

            return result;

        } catch (Exception e) {
            throw new RuntimeException("Metadata keyword extraction failed", e);
        }
    }

    public String extractCaption(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes)) {

            Metadata metadata = ImageMetadataReader.readMetadata(is);

            for (Directory dir : metadata.getDirectories()) {
                for (Tag tag : dir.getTags()) {

                    String tagName = tag.getTagName();

                    if (isCaptionTag(tagName)) {
                        String value = safeTrim(tag.getDescription());

                        if (value != null && !value.isEmpty()) {
                            log.debug("metadata.caption.found tag={} value={}", tagName, value);
                            return value;
                        }
                    }
                }
            }

            log.debug("metadata.caption.not_found");
            return null;

        } catch (Exception e) {
            throw new RuntimeException("Metadata caption extraction failed", e);
        }
    }

    public String calculateMd5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            return Hex.encodeHexString(digest);
        } catch (Exception e) {
            throw new RuntimeException("MD5 calculation failed", e);
        }
    }

    // =========================
    // Helpers
    // =========================

    private boolean isKeywordTag(String tagName) {
        if (tagName == null) return false;

        String name = tagName.toLowerCase();

        return name.contains("keyword"); // catches IPTC + XMP variants
    }

    private boolean isCaptionTag(String tagName) {
        if (tagName == null) return false;

        String name = tagName.toLowerCase();

        return name.contains("caption") ||
                name.contains("description"); // covers IPTC + EXIF + XMP
    }

//    private void parseKeywords(String raw, Map<String, String> map) {
//        if (raw == null || raw.isBlank()) return;
//
//        // Example:
//        // "uuid:abc-123, bl:3001, ic:M, sealed:true"
//        String[] parts = raw.split(",");
//
//        for (String part : parts) {
//            String[] kv = part.trim().split(":", 2);
//
//            if (kv.length == 2) {
//                String key = kv[0].trim();
//                String value = kv[1].trim();
//
//                if (!key.isEmpty() && !value.isEmpty()) {
//                    map.put(key, value);
//                }
//            }
//        }
//    }

    private Map<String, String> parseKeywordString(String raw) {

        Map<String, String> result = new HashMap<>();

        if (raw == null || raw.isBlank()) {
            return result;
        }

        String[] pairs = raw.split(";");

        for (String pair : pairs) {

            String trimmed = pair.trim();

            if (trimmed.isBlank()) {
                continue;
            }

            int idx = trimmed.indexOf(':');

            // Presence-only flag
            if (idx < 0) {

                result.put(
                        trimmed.toLowerCase(),
                        "true"
                );

                continue;
            }

            // Malformed
            if (idx == 0) {
                continue;
            }

            String key =
                    trimmed.substring(0, idx)
                            .trim()
                            .toLowerCase();

            String value =
                    trimmed.substring(idx + 1)
                            .trim();

            result.put(key, value);
        }

        return result;
    }


    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }
}