package io.legohunter.ingress.source.photo.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.legohunter.ingress.source.photo.exception.LegoImagingException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Data
@Slf4j
public class PhotoMetaData {
    @JsonCreator
    public PhotoMetaData(@JsonProperty("path") Path path, @JsonProperty("filename") Path filename) {
        this.path = path;
        this.filename = filename;
    }

    public PhotoMetaData(Path path) {
        this.path = path.getParent();
        this.filename = path.getFileName();
    }

    @JsonIgnore
    private Path path;

    private Path filename;
    private Map<String, String> keywords = new HashMap<>();
    private String md5;
    private String photoId;
    private int uploadReturnCode;
    private LocalDateTime uploadedTimeStamp;
    private boolean primary;
    private boolean changed;

    @JsonProperty("filename")
    public String getFilenameString() {
        return filename.getFileName().toString();
    }

    @JsonIgnore
    public Path getAbsolutePath() {
        return path.resolve(getFilename());
    }

    @JsonIgnore
    public String getKeyword(String keyword) {
        return Optional.ofNullable(keywords)
                .map(m -> m.get(keyword))
                .orElse(null);
    }

    @JsonProperty("primary")
    public boolean getPrimary() {
        return (this.primary || Optional.ofNullable(keywords)
                .map(m -> m.containsKey("primary"))
                .orElse(false));
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public void setPrimary(String primary) {
        this.primary = Optional.ofNullable(primary).map(p -> p.equals("primary")).orElse(false);
    }

    public String getMd5() {
        return Optional.ofNullable(this.md5).orElseGet(() -> {
            StopWatch timer = new StopWatch();
            timer.start();
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("MD5");
                InputStream is = Files.newInputStream(this.getAbsolutePath());
                BufferedInputStream bis = new BufferedInputStream(is);
                DigestInputStream dis = new DigestInputStream(bis, md);
                byte[] bytes = new byte[8192];
                while ((dis.read(bytes)) != -1) ;
                byte[] digest = Optional.ofNullable(md)
                        .map(MessageDigest::digest)
                        .orElseThrow(() -> new LegoImagingException("Unable to compute MD5 hash for file [" + this.getAbsolutePath() + "]"));
                this.md5 = bytesToHex(digest);
            } catch (Exception e) {
                throw new LegoImagingException(e);
            } finally {
                timer.stop();
            }
            log.info("computed digest [{}] for file [{}] in [{}] ms", this.md5, this.getAbsolutePath(), timer.getTotalTimeMillis());
            return this.md5;
        });
    }

    public Path move(Path targetDirectory) {
        if (!canMove()) {
            log.warn("Cannot move image file [{}] - missing required keywords", getAbsolutePath());
        } else {
            try {
                if (!Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(targetDirectory);
                    log.debug("Created directory path [{}]", targetDirectory);
                }
                Path targetFile = targetDirectory.resolve(getFilenameString());
                if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(targetFile);
                    log.debug("Deleted existing image [{}]", targetFile);
                }
                log.info("Moving image from [{}] to [{}]", getAbsolutePath(), targetFile);
                Files.copy(getAbsolutePath(), targetFile, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Copied image from [{}] to [{}]", getAbsolutePath(), targetFile);
                Files.delete(getAbsolutePath());
                log.debug("Deleted old image [{}]", getAbsolutePath());
                this.path = targetDirectory;
            } catch (IOException e) {
                throw new LegoImagingException(e);
            }
        }
        return this.path;
    }

    @JsonIgnore
    public boolean canMove() {
        return (keywords.containsKey("uuid") && keywords.containsKey("bl"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhotoMetaData that = (PhotoMetaData) o;
        return Objects.equals(getMd5(), that.getMd5());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getMd5());
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
}