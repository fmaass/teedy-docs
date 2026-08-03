package com.sismics.util.mime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility to check MIME types.
 *
 * @author bgamard
 */
public class MimeTypeUtil {
    /**
     * Length of the RIFF/WEBP container preamble: "RIFF" (4) + the little-endian chunk size (4) +
     * "WEBP" (4).
     */
    private static final int WEBP_SIGNATURE_LENGTH = 12;

    /**
     * Try to guess the MIME type of a file.
     *
     * <p>The file's BYTES are consulted first for the formats listed in {@link #guessMimeTypeFromContent},
     * because the two inference sources below it are both name-based: {@link Files#probeContentType}
     * resolves through the platform's extension table and the {@link URLConnection} filename map is
     * pure extension matching. A file uploaded without a name, or with one the host's table does not
     * know, would otherwise be stored as {@code application/octet-stream} and lose its preview.
     *
     * @param file File to inspect
     * @param name File name
     * @return MIME type
     * @throws IOException e
     */
    public static String guessMimeType(Path file, String name) throws IOException {
        String mimeType = guessMimeTypeFromContent(file);

        if (mimeType == null) {
            mimeType = Files.probeContentType(file);
        }

        if (mimeType == null && name != null) {
            mimeType = URLConnection.getFileNameMap().getContentTypeFor(name);
        }

        if (mimeType == null) {
            return MimeType.DEFAULT;
        }

        return mimeType;
    }

    /**
     * Identify a file from its leading bytes, for formats the name-based detectors cannot be relied
     * upon to know.
     *
     * <p>Only WebP is sniffed here: it is the one supported format with no entry in the JDK's
     * built-in type table, so on a host without a {@code .webp} line in its mime.types it is
     * invisible to every other detector. The signature is the RIFF container header — {@code "RIFF"}
     * at offset 0 and {@code "WEBP"} at offset 8 — which covers all three container flavours (the
     * lossy {@code VP8 }, lossless {@code VP8L} and extended {@code VP8X} chunks that follow).
     *
     * @param file File to inspect
     * @return The MIME type, or null when the content is not recognized
     * @throws IOException e
     */
    private static String guessMimeTypeFromContent(Path file) throws IOException {
        byte[] header = new byte[WEBP_SIGNATURE_LENGTH];
        int read = 0;
        try (InputStream inputStream = Files.newInputStream(file)) {
            while (read < header.length) {
                int count = inputStream.read(header, read, header.length - read);
                if (count == -1) {
                    break;
                }
                read += count;
            }
        }

        if (read == WEBP_SIGNATURE_LENGTH
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return MimeType.IMAGE_WEBP;
        }

        return null;
    }

    /**
     * Get a file extension linked to a MIME type.
     * 
     * @param mimeType MIME type
     * @return File extension
     */
    public static String getFileExtension(String mimeType) {
        switch (mimeType) {
            case MimeType.APPLICATION_ZIP:
                return "zip";
            case MimeType.IMAGE_GIF:
                return "gif";
            case MimeType.IMAGE_JPEG:
                return "jpg";
            case MimeType.IMAGE_PNG:
                return "png";
            case MimeType.IMAGE_WEBP:
                return "webp";
            case MimeType.APPLICATION_PDF:
                return "pdf";
            case MimeType.OPEN_DOCUMENT_TEXT:
                return "odt";
            case MimeType.OFFICE_DOCUMENT:
                return "docx";
            case MimeType.TEXT_PLAIN:
                return "txt";
            case MimeType.TEXT_CSV:
                return "csv";
            case MimeType.VIDEO_MP4:
                return "mp4";
            case MimeType.VIDEO_WEBM:
                return "webm";
            case MimeType.MESSAGE_RFC822:
                return "eml";
            default:
                return "bin";
        }
    }
}
