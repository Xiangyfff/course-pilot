package com.courseplanner.extract;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本 / Markdown。先按 UTF-8 严格解码，失败再退 GBK——
 * 中文教务系统导出的 txt 经常是 GBK。
 */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv");
    }

    @Override
    public String extractText(byte[] content, String fileName) {
        return decode(content, StandardCharsets.UTF_8, Charset.forName("GBK"));
    }

    static String decode(byte[] content, Charset primary, Charset fallback) {
        try {
            return primary.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, fallback);
        }
    }
}
