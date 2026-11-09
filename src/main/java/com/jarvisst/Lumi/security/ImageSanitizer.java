package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;

/**
 * 사용자가 올린 이미지를 믿지 않고 다시 만들어서 쓴다 (정보보안개론 5장 코드 보안 + 6장 악성코드 + 사회공학 장 종합).
 *
 *  1) 크기 경계 검사: 원본 3MB, 가로×세로 2,500만 화소까지(압축 폭탄처럼 작은 파일이 거대한 그림으로 풀리는 공격 방어).
 *  2) 파일 시그니처 검사: 선언한 형식(image/png 등)과 실제 첫 바이트가 맞지 않으면 거부(확장자·형식 위장 방어).
 *  3) 재인코딩: 읽어서 새 JPEG로 다시 쓴다. 파일에 숨은 데이터(스크립트가 섞인 이미지, 추가 페이로드)와
 *     EXIF 메타데이터(촬영 위치·기기·시각)가 모두 사라진다. 긴 변은 512px로 줄인다(감정 분석에는 충분하고 전송량도 작다).
 *  4) 임시 파일 금지: ImageIO 디스크 캐시를 끄고 전부 메모리에서 처리해 디스크에 흔적이 남지 않게 한다.
 */
@Component
public class ImageSanitizer {

    public static final int MAX_RAW_BYTES = 3 * 1024 * 1024;
    public static final long MAX_PIXELS = 25_000_000L;
    public static final int MAX_SIDE = 512; // 감정 분석은 224px 정도로 줄여 쓰므로 이 정도면 충분
    private static final float JPEG_QUALITY = 0.85f;

    static {
        ImageIO.setUseCache(false);
    }

    /** 검증을 통과해 다시 만든 이미지(항상 JPEG). */
    public record SanitizedImage(String mimeType, String base64, int width, int height, int originalBytes) {
    }

    /** 사용자에게 그대로 보여줄 수 있는 거부 사유를 담는다. */
    public static class InvalidImageException extends Exception {
        public InvalidImageException(String userMessage) {
            super(userMessage);
        }
    }

    public SanitizedImage sanitize(String base64, String declaredMime) throws InvalidImageException {
        if (base64 == null || base64.isBlank()) {
            throw new InvalidImageException("이미지가 비어 있습니다.");
        }
        if (base64.length() > (MAX_RAW_BYTES / 3 + 1) * 4 + 8) {
            throw new InvalidImageException("이미지가 너무 큽니다. 3MB 이하로 올려주세요.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidImageException("이미지 데이터를 읽을 수 없습니다.");
        }
        if (raw.length > MAX_RAW_BYTES) {
            throw new InvalidImageException("이미지가 너무 큽니다. 3MB 이하로 올려주세요.");
        }
        String kind = sniff(raw);
        if (kind == null) {
            throw new InvalidImageException("지원하지 않는 이미지 형식입니다. JPG, PNG, GIF만 올릴 수 있습니다.");
        }
        if (declaredMime == null || !declaredMime.equals(kind)) {
            throw new InvalidImageException("이미지 형식 정보가 실제 파일과 다릅니다.");
        }

        BufferedImage image = readBounded(raw);
        BufferedImage scaled = scaleToRgb(image);
        byte[] jpeg = encodeJpeg(scaled);
        return new SanitizedImage("image/jpeg", Base64.getEncoder().encodeToString(jpeg),
                scaled.getWidth(), scaled.getHeight(), raw.length);
    }

    /** 첫 바이트(매직 넘버)로 실제 형식을 판별한다. JPEG/PNG/GIF 외에는 null. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        return null;
    }

    /** 화소 수를 먼저 확인하고(디코딩 전) 한도 안일 때만 실제로 디코딩한다. */
    private BufferedImage readBounded(byte[] raw) throws InvalidImageException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidImageException("이미지를 읽을 수 없습니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) {
                    throw new InvalidImageException("이미지 해상도가 너무 큽니다.");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("이미지를 읽을 수 없습니다.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException e) {
            throw e;
        } catch (Exception | OutOfMemoryError e) {
            throw new InvalidImageException("이미지를 읽을 수 없습니다.");
        }
    }

    /** 흰 배경 위에 그려 투명도를 없애고, 긴 변이 MAX_SIDE를 넘으면 비율을 유지해 줄인다. */
    private BufferedImage scaleToRgb(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double ratio = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encodeJpeg(BufferedImage image) throws InvalidImageException {
        ImageWriter writer = null;
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new InvalidImageException("이미지를 처리할 수 없습니다.");
            }
            writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(out);
                // 메타데이터를 넘기지 않으므로 EXIF 등은 새 파일에 들어가지 않는다.
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bos.toByteArray();
        } catch (InvalidImageException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidImageException("이미지를 처리할 수 없습니다.");
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }
    }
}
