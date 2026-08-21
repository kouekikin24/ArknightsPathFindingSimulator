import javax.swing.text.DocumentFilter;
import javax.swing.text.NumberFormatter;
import java.text.DecimalFormat;

/**
 * NumberFormatter whose document filter normalizes IME full-width input.
 * Swing re-installs the formatter's filter on every spinner setValue, which
 * would silently drop a wrapper installed from outside; overriding the
 * factory method makes Swing reinstall the normalizing filter every time.
 */
final class ImeNumberFormatter extends NumberFormatter {
    ImeNumberFormatter(DecimalFormat format) {
        super(format);
    }

    @Override
    protected DocumentFilter getDocumentFilter() {
        return new ImeNumericFilter(super.getDocumentFilter());
    }
}
