package com.github.cabutchei.wtpcomponent.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

/**
 * Small helpers to map between LSP positions and string offsets while applying changes.
 */
public final class TextDocumentUtils {

    private TextDocumentUtils() {
    }

    public static int offsetAt(String text, Position position) {
        if (text == null || position == null) return 0;
        int line = Math.max(position.getLine(), 0);
        int character = Math.max(position.getCharacter(), 0);

        int offset = 0;
        int currentLine = 0;

        while (currentLine < line && offset < text.length()) {
            int nextLineBreak = text.indexOf('\n', offset);
            if (nextLineBreak == -1) {
                return text.length();
            }
            offset = nextLineBreak + 1;
            currentLine++;
        }

        return Math.min(offset + character, text.length());
    }

    public static Position positionAt(String text, int offset) {
        if (text == null) return new Position(0, 0);
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safeOffset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int character = safeOffset - lineStart;
        return new Position(line, character);
    }

    public static String applyChange(String text, TextDocumentContentChangeEvent change) {
        if (change.getRange() == null) {
            return change.getText();
        }
        Range range = change.getRange();
        int start = offsetAt(text, range.getStart());
        int end = offsetAt(text, range.getEnd());
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(0, Math.min(Math.max(safeStart, end), text.length()));
        return text.substring(0, safeStart) + change.getText() + text.substring(safeEnd);
    }
}

