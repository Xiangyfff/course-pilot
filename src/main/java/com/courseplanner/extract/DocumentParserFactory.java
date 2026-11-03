package com.courseplanner.extract;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;

    public DocumentParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser resolve(String fileName, byte[] content) {
        return parsers.stream()
                .filter(p -> p.supports(fileName, content))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.FILE_INVALID,
                        "不支持的文件类型：目前支持 PDF / TXT / MD / CSV"));
    }

    public String parse(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FILE_INVALID, "文件为空");
        }
        String text = resolve(fileName, content).extractText(content, fileName);
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.FILE_INVALID, "没有从文件里提取到文字，可能是扫描件");
        }
        return text;
    }
}
