package com.pharmacore.importing.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Delimited text reader for exports of other pharmacy programs: detects the charset (UTF-8 with
 * or without BOM, otherwise Windows-1252 as written by Excel and legacy Windows software) and
 * the delimiter (semicolon, comma, tab, pipe), honours RFC 4180 quoting and embedded line breaks.
 */
public final class CsvReader {

    private static final char[] CANDIDATE_DELIMITERS = {';', ',', '\t', '|'};
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CsvReader() {
    }

    public static TabularFile read(byte[] content) {
        Charset charset = detectCharset(content);
        String text = decode(content, charset);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = detectDelimiter(text);
        List<List<String>> records = parse(text, delimiter);
        while (!records.isEmpty() && records.getFirst().stream().allMatch(String::isBlank)) {
            records.removeFirst();
        }
        if (records.isEmpty()) {
            return new TabularFile("CSV", charset.name(), String.valueOf(delimiter), List.of(), List.of());
        }
        List<String> headers = records.getFirst().stream().map(String::trim).toList();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(record.stream().map(String::trim).toList());
        }
        return new TabularFile("CSV", charset.name(), String.valueOf(delimiter), headers, rows);
    }

    static Charset detectCharset(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return WINDOWS_1252;
        }
    }

    private static String decode(byte[] content, Charset charset) {
        return new String(content, charset);
    }

    static char detectDelimiter(String text) {
        int end = text.length();
        int lines = 0;
        for (int i = 0; i < text.length() && lines < 20; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
                end = i;
            }
        }
        String sample = text.substring(0, end);
        char best = ';';
        long bestCount = -1;
        for (char candidate : CANDIDATE_DELIMITERS) {
            long count = sample.chars().filter(c -> c == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
