package com.duabiskuttelur.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * Produces a small base64 JPEG thumbnail so history never stores full photos.
 *
 * <p>Decoding goes through {@link ImageReader} rather than the one-line
 * {@code ImageIO.read}, because these bytes are whatever a user uploaded and
 * how much they decode to is not bounded by how many were uploaded. A header
 * can declare any dimensions it likes: a ~1 KB PNG claiming 30000x30000 passes
 * the 10 MB multipart limit and then asks {@code ImageIO.read} for ~3.6 GB of
 * heap — a single-request OOM on a container with no memory limit.
 *
 * <p>Two defences, guarding <em>different</em> resources. Neither subsumes the
 * other, so removing either one reopens something:
 *
 * <ul>
 *   <li><b>Subsampling bounds memory.</b> The decoder is asked for roughly
 *       thumbnail-sized output, so the destination raster is sized from the
 *       thumbnail rather than from the source — the full-size raster is never
 *       allocated even for an image that claims to be enormous. This, not the
 *       pixel cap, is what actually defuses the bomb above.</li>
 *   <li><b>The pixel cap bounds CPU.</b> Subsampling still has to stream the
 *       whole compressed image to pick pixels out of it, so a gigapixel input
 *       would otherwise be a very expensive way to arrive at "too big". Reading
 *       dimensions from the header costs nothing and refuses it up front,
 *       before a single scanline is decompressed.</li>
 * </ul>
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int MAX_EDGE = 128;

    /**
     * Ceiling on total pixels, checked against the header. Comfortably above any
     * real photograph that could fit under the 10 MB multipart limit — the app's
     * own client compresses to 1568px (meals) / 2400px (menus), and even an
     * untranscoded flagship-phone shot lands well below this — while staying far
     * under the gigapixel range where merely reading the stream is the attack.
     */
    private static final long MAX_PIXELS = 50_000_000L;

    /**
     * Decode to roughly this multiple of the target edge, then scale down
     * properly. Subsampling picks pixels rather than averaging them, so going
     * straight to 128px would alias badly; leaving headroom for a real bilinear
     * pass afterwards keeps quality at least as good as the old full-decode
     * path while still never allocating the full raster.
     */
    private static final int SUBSAMPLE_HEADROOM = 2;

    public String thumbnailDataUrl(byte[] imageBytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null; // not a format this JVM can decode
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                BufferedImage source = decodeWithinLimits(reader);
                if (source == null) {
                    return null;
                }
                return encode(scaleToThumbnail(source));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.warn("Thumbnail generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Reads the header, refuses anything oversized, then decodes only as many pixels as the thumbnail needs. */
    private BufferedImage decodeWithinLimits(ImageReader reader) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (exceedsPixelCap(width, height)) {
            log.warn("Refusing to decode a {}x{} image: {} pixels exceeds the {} cap",
                    width, height, (long) width * height, MAX_PIXELS);
            return null;
        }

        ImageReadParam param = reader.getDefaultReadParam();
        int step = subsamplingStep(Math.max(width, height));
        if (step > 1) {
            param.setSourceSubsampling(step, step, 0, 0);
        }
        return reader.read(0, param);
    }

    /**
     * Package-visible for direct testing. The {@code long} cast is the whole
     * point: two dimensions well inside {@code int} multiply out past it, and
     * an {@code int} product wraps negative — which compares as comfortably
     * under the cap and would wave the largest images straight through.
     */
    static boolean exceedsPixelCap(int width, int height) {
        return (long) width * height > MAX_PIXELS;
    }

    /** How many source pixels to skip per decoded pixel; 1 means decode everything (already small). */
    static int subsamplingStep(int longestEdge) {
        return Math.max(1, longestEdge / (MAX_EDGE * SUBSAMPLE_HEADROOM));
    }

    private BufferedImage scaleToThumbnail(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return thumb;
    }

    private String encode(BufferedImage thumb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(thumb, "jpg", out);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
