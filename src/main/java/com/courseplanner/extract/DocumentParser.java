package com.courseplanner.extract;

public interface DocumentParser {

    boolean supports(String fileName, byte[] content);

    /** 只负责「把字节变成文字」，不负责理解文字。 */
    String extractText(byte[] content, String fileName);
}
