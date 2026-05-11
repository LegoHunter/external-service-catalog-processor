package io.legohunter.ingress.source.photo.service;

import lombok.extern.slf4j.Slf4j;
import org.imgscalr.Scalr;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
@Service
public class ImageScalingService {

    private static final long MAX_SIZE = 2_000_000L; // 2MB

    public byte[] scale(byte[] originalBytes) {

        try {
            BufferedImage originalImage =
                    ImageIO.read(new ByteArrayInputStream(originalBytes));

            if (originalImage == null) {
                throw new RuntimeException("Invalid image data");
            }

            int originalWidth = originalImage.getWidth();

            double minScale = 0.1;
            double maxScale = 1.0;

            byte[] bestResult = originalBytes;

            // Binary search for optimal size under 2MB
            for (int i = 0; i < 10; i++) {

                double scale = (minScale + maxScale) / 2.0;

                int targetWidth = (int) (originalWidth * scale);

                BufferedImage resized =
                        Scalr.resize(originalImage, targetWidth);

                byte[] candidate = toJpegBytes(resized);

                if (candidate.length > MAX_SIZE) {
                    maxScale = scale;
                } else {
                    bestResult = candidate;
                    minScale = scale;
                }
            }

            log.debug("Scaling complete: original={} bytes, scaled={} bytes",
                    originalBytes.length, bestResult.length);

            return bestResult;

        } catch (Exception e) {
            throw new RuntimeException("Image scaling failed", e);
        }
    }

    private byte[] toJpegBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }
}