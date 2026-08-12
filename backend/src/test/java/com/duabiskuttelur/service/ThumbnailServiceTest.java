package com.duabiskuttelur.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These bytes are whatever a user uploaded, and how much they decode to is not
 * bounded by how many were uploaded. Two separate mechanisms keep that in
 * check — subsampling for memory, the pixel cap for CPU — and because each
 * alone is enough to make a hostile image come back as null, a test that only
 * checked the return value would pass with either one deleted. The assertions
 * below are picked to fail if either goes missing.
 */
class ThumbnailServiceTest {

    private final ThumbnailService service = new ThumbnailService();

    private static final String DATA_URL_PREFIX = "data:image/jpeg;base64,";

    private ch.qos.logback.classic.Logger serviceLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureServiceLogs() {
        serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThumbnailService.class);
        captured = new ListAppender<>();
        captured.start();
        serviceLogger.addAppender(captured);
    }

    @AfterEach
    void releaseServiceLogs() {
        serviceLogger.detachAppender(captured);
        captured.stop();
    }

    private List<String> loggedMessages() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // --- the two pieces of arithmetic that are easy to get quietly wrong ---

    @Test
    void pixelCapSurvivesDimensionsThatOverflowAnIntMultiply() {
        // 60000 x 60000 is 3.6e9 pixels; as an int product that wraps to a
        // negative number, which reads as far *under* any cap. Getting this
        // wrong doesn't fail loudly — it waves through exactly the images the
        // cap exists to stop.
        assertTrue(ThumbnailService.exceedsPixelCap(60_000, 60_000),
                "an int-overflowing product must not read as under the cap");
        assertTrue(ThumbnailService.exceedsPixelCap(30_000, 30_000));
    }

    @Test
    void pixelCapAcceptsAnythingTheAppItselfWouldUpload() {
        // The client compresses meals to 1568px and menus to 2400px on the long
        // edge (imageUtils.js); an untranscoded phone photo is larger but still
        // far under the cap. None of these may be rejected.
        assertTrue(!ThumbnailService.exceedsPixelCap(1568, 1568), "a compressed meal photo");
        assertTrue(!ThumbnailService.exceedsPixelCap(2400, 2400), "a compressed menu photo");
        assertTrue(!ThumbnailService.exceedsPixelCap(8000, 6000), "a 48MP phone photo");
    }

    @Test
    void subsamplingAsksForRoughlyTwiceTheThumbnailEdge() {
        assertEquals(15, ThumbnailService.subsamplingStep(4000), "4000px -> ~267px decoded, not 4000px");
        assertEquals(1, ThumbnailService.subsamplingStep(200), "already small — decode it whole");
        assertEquals(1, ThumbnailService.subsamplingStep(1), "must never return 0, which would be an invalid step");
    }

    // --- end to end ---

    /**
     * Asserting only that this returns null would pass with the cap deleted:
     * subsampling already keeps the destination raster small, so an oversized
     * image fails later, on the decode, and returns null either way. The cap is
     * there to stop the decoder streaming a gigapixel image at all, and the log
     * line is what distinguishes "refused at the header" from "tried it and
     * fell over" — so that is what this checks.
     */
    @Test
    void refusesAHeaderDeclaringMorePixelsThanTheCapBeforeDecodingAnything() throws IOException {
        // A PNG header is free to claim any dimensions; nothing forces the file
        // to be large. This one is a few hundred bytes claiming 900M pixels.
        byte[] bomb = pngDeclaring(30_000, 30_000);
        assertTrue(bomb.length < 1_000,
                "the point is the amplification: " + bomb.length + " bytes claiming 900M pixels");

        assertNull(service.thumbnailDataUrl(bomb));
        assertTrue(loggedMessages().stream().anyMatch(m -> m.startsWith("Refusing to decode")),
                "expected rejection at the header, but the reader was handed the image anyway: " + loggedMessages());
    }

    @Test
    void scalesARealPhotoDownToTheEdgeCapKeepingItsAspectRatio() throws IOException {
        BufferedImage thumb = decode(service.thumbnailDataUrl(jpeg(1000, 500)));

        assertNotNull(thumb);
        assertEquals(128, Math.max(thumb.getWidth(), thumb.getHeight()), "long edge should hit the 128px cap");
        assertEquals(64, thumb.getHeight(), "2:1 source should stay 2:1");
    }

    @Test
    void leavesAnImageAlreadySmallerThanTheCapAtItsOwnSize() throws IOException {
        BufferedImage thumb = decode(service.thumbnailDataUrl(jpeg(40, 30)));

        assertNotNull(thumb);
        assertEquals(40, thumb.getWidth(), "no upscaling");
        assertEquals(30, thumb.getHeight());
    }

    @Test
    void returnsNullRatherThanThrowingForBytesThatArentAnImage() {
        assertNull(service.thumbnailDataUrl("this is not an image".getBytes(StandardCharsets.UTF_8)));
        assertNull(service.thumbnailDataUrl(new byte[0]));
    }

    // --- fixtures ---

    private static BufferedImage decode(String dataUrl) throws IOException {
        assertNotNull(dataUrl, "expected a thumbnail data URL");
        assertTrue(dataUrl.startsWith(DATA_URL_PREFIX), "unexpected data URL prefix: " + dataUrl.substring(0, 32));
        byte[] jpeg = Base64.getDecoder().decode(dataUrl.substring(DATA_URL_PREFIX.length()));
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        // Some actual variation, so JPEG doesn't collapse it to nothing and the
        // scaling assertions are measuring a real resample.
        for (int x = 0; x < width; x += 16) {
            g.setColor(new Color((x * 7) % 255, (x * 13) % 255, 120));
            g.fillRect(x, 0, 16, height);
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /**
     * A structurally valid PNG whose IHDR claims {@code width x height} while
     * the file itself stays tiny — the shape of a decompression bomb, where
     * {@code ImageIO.read} would size its destination raster straight from
     * those dimensions.
     *
     * <p>The IDAT is real (a valid zlib stream) but holds nowhere near enough
     * scanlines for the declared size. That matters: without a well-formed
     * IDAT the reader would stop early for its own reasons and the test would
     * pass without ever reaching the code under test.
     */
    private static byte[] pngDeclaring(int width, int height) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);   // bit depth
        ihdr.put((byte) 2);   // colour type: truecolour RGB
        ihdr.put((byte) 0);   // compression
        ihdr.put((byte) 0);   // filter
        ihdr.put((byte) 0);   // interlace
        writeChunk(png, "IHDR", ihdr.array());

        // IDAT has to be present and well-formed, or the reader stops at the
        // header for the wrong reason and the test would pass vacuously.
        writeChunk(png, "IDAT", deflate(new byte[16]));
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buffer = new byte[64];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
