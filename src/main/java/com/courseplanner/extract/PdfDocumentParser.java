package com.courseplanner.extract;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final byte[] MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

    @Override
    public boolean supports(String fileName, byte[] content) {
        // 用魔数判断而不是扩展名：改个后缀就能绕过的校验没有意义
        if (content.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (content[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String extractText(byte[] content, String fileName) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (Exception e) {
            throw new BizException(ErrorCode.FILE_INVALID, "PDF 解析失败：" + e.getMessage(), e);
        }
    }
}
