package com.duabiskuttelur.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Produces a small base64 JPEG thumbnail so history never stores full photos.
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int MAX_EDGE = 128;

    public String thumbnailDataUrl(byte[] imageBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                return null;
            }
            double scale = (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * Math.min(1.0, scale)));
            int height = Math.max(1, (int) Math.round(source.getHeight() * Math.min(1.0, scale)));

            BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", out);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.warn("Thumbnail generation failed: {}", e.getMessage());
            return null;
        }
    }
}
