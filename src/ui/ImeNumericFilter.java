import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/**
 * A Chinese IME types full-width characters (１２３, 。, ．) that numeric
 * formatters cannot parse; on commit the field silently reverts to its old
 * value, which looks like "the input was ignored". This filter maps those
 * characters to ASCII at insertion time and then defers to the formatter's
 * own filter, so typing never reaches a dead end.
 */
final class ImeNumericFilter extends DocumentFilter {
    private final DocumentFilter previous;

    ImeNumericFilter(DocumentFilter previous) {
        this.previous = previous;
    }

    @Override
    public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
            throws BadLocationException {
        String normalized = normalize(string);
        if (previous != null) {
            previous.insertString(fb, offset, normalized, attr);
        } else {
            super.insertString(fb, offset, normalized, attr);
        }
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
            throws BadLocationException {
        String normalized = normalize(text);
        if (previous != null) {
            previous.replace(fb, offset, length, normalized, attrs);
        } else {
            super.replace(fb, offset, length, normalized, attrs);
        }
    }

    @Override
    public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
        if (previous != null) {
            previous.remove(fb, offset, length);
        } else {
            super.remove(fb, offset, length);
        }
    }

    /** Maps full-width digits and punctuation to ASCII; other text is returned unchanged. */
    static String normalize(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char mapped = c;
            if (c >= '０' && c <= '９') {
                mapped = (char) ('0' + (c - '０'));
            } else if (c == '。' || c == '．') {
                mapped = '.';
            } else if (c == '－') {
                mapped = '-';
            } else if (c == '＋') {
                mapped = '+';
            }
            if (mapped != c) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                    out.append(text, 0, i);
                }
                out.append(mapped);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }
}
