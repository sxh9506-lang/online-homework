package com.onlinehomework.file.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class FileContentInspector {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    public String detect(Path file) throws IOException {
        long length = Files.size(file);
        if (length == 0) return "";
        byte[] prefix = new byte[(int) Math.min(8, length)];
        try (var input = Files.newInputStream(file)) {
            int read = input.read(prefix);
            if (read >= 5 && prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D' && prefix[3] == 'F' && prefix[4] == '-') {
                return "application/pdf";
            }
            if (read >= 8 && (prefix[0] & 0xff) == 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G'
                    && prefix[4] == 0x0d && prefix[5] == 0x0a && prefix[6] == 0x1a && prefix[7] == 0x0a) {
                return "image/png";
            }
            if (read >= 3 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xd8 && (prefix[2] & 0xff) == 0xff) {
                return "image/jpeg";
            }
            if (read >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                    && (prefix[2] == 3 || prefix[2] == 5 || prefix[2] == 7)
                    && (prefix[3] == 4 || prefix[3] == 6 || prefix[3] == 8)) {
                return detectOfficePackage(file);
            }
            return null;
        }
    }

    private String detectOfficePackage(Path file) throws IOException {
        boolean contentTypes = false;
        boolean word = false;
        boolean sheet = false;
        boolean presentation = false;
        int entries = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++entries > 10000) return null;
                String name = entry.getName();
                contentTypes |= name.equals("[Content_Types].xml");
                word |= name.equals("word/document.xml");
                sheet |= name.equals("xl/workbook.xml");
                presentation |= name.equals("ppt/presentation.xml");
                if (word && sheet || word && presentation || sheet && presentation) return null;
            }
        } catch (IOException exception) {
            return null;
        }
        if (!contentTypes) return null;
        if (word) return DOCX;
        if (sheet) return XLSX;
        if (presentation) return PPTX;
        return null;
    }
}
