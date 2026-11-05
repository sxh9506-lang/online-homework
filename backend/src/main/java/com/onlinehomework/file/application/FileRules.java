package com.onlinehomework.file.application;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class FileRules {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    public void validateDeclaration(String fileName, String contentType, long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > MAX_BYTES) {
            throw DomainException.invalid("FILE_TOO_LARGE", "A file must be no larger than 20 MiB (20,971,520 bytes).");
        }
        if (sizeBytes == 0) {
            throw DomainException.invalid("FILE_EMPTY", "Empty files are not accepted.");
        }
        String extension = extension(fileName);
        String expectedMime = EXTENSION_TO_MIME.get(extension);
        if (expectedMime == null) {
            throw DomainException.invalid("UNSUPPORTED_FILE_TYPE", "This file type is not supported.");
        }
        if (!expectedMime.equalsIgnoreCase(contentType == null ? "" : contentType.trim())) {
            throw DomainException.invalid("FILE_CONTENT_MISMATCH", "The file extension and declared content type do not match.");
        }
    }

    public void validateActual(String fileName, String declaredContentType, long declaredSize,
                               PrivateObjectStorage.StoredObject stored) {
        if (stored.sizeBytes() == 0) {
            throw DomainException.unprocessable("FILE_EMPTY", "Empty files are not accepted.");
        }
        if (stored.sizeBytes() > MAX_BYTES) {
            throw DomainException.unprocessable("FILE_TOO_LARGE", "A file must be no larger than 20 MiB (20,971,520 bytes).");
        }
        if (stored.sizeBytes() != declaredSize) {
            throw DomainException.unprocessable("FILE_CONTENT_MISMATCH", "Uploaded size does not match the declared size.");
        }
        String declared = EXTENSION_TO_MIME.get(extension(fileName));
        if (declared == null || !declared.equalsIgnoreCase(declaredContentType)
                || !declared.equalsIgnoreCase(stored.actualContentType())) {
            throw DomainException.unprocessable("FILE_CONTENT_MISMATCH", "The uploaded content does not match the declared file type.");
        }
    }

    public String sanitizeFileName(String fileName) {
        String normalized = fileName == null ? "file" : fileName.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        baseName = baseName.replaceAll("[\\p{Cntrl}]", "_");
        if (baseName.isBlank() || baseName.equals(".") || baseName.equals("..")) {
            return "file";
        }
        return baseName.length() > 255 ? baseName.substring(baseName.length() - 255) : baseName;
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        String basename = fileName.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        int dot = basename.lastIndexOf('.');
        return dot < 0 ? "" : basename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
