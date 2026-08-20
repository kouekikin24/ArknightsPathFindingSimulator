import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact mapping between the UI time field and the fixed 30 FPS frame index. */
public final class SimulationTime {
    public static final int FPS = 30;
    private static final BigDecimal FPS_DECIMAL = BigDecimal.valueOf(FPS);

    private SimulationTime() {
    }

    /**
     * Accepted forms:
     * <ul>
     *     <li>digits only, interpreted as a frame number (for example {@code 16});</li>
     *     <li>{@code n/30}, interpreted as an exact frame number;</li>
     *     <li>a finite non-negative decimal number of seconds whose product with
     *         30 is an integer.</li>
     * </ul>
     */
    public static long parseFrame(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Time is required");
        }
        String text = normalize(input.strip());
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Time is required");
        }

        if (text.matches("[0-9]+")) {
            return toLong(new BigInteger(text), "Frame is too large");
        }

        int slash = text.indexOf('/');
        if (slash >= 0) {
            if (slash != text.lastIndexOf('/') || !text.substring(slash + 1).strip().equals("30")
                    || !text.substring(0, slash).strip().matches("[0-9]+")) {
                throw new IllegalArgumentException("Fractional time must use the exact n/30 form");
            }
            return toLong(new BigInteger(text.substring(0, slash).strip()), "Frame is too large");
        }

        final BigDecimal seconds;
        try {
            seconds = new BigDecimal(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid time: " + text, error);
        }
        if (seconds.signum() < 0) {
            throw new IllegalArgumentException("Time must be non-negative");
        }

        BigDecimal frameValue = seconds.multiply(FPS_DECIMAL);
        try {
            return toLong(frameValue.toBigIntegerExact(), "Frame is too large");
        } catch (ArithmeticException notAnIntegerFrame) {
            BigInteger lower = frameValue.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
            BigInteger upper = frameValue.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
            throw new IllegalArgumentException(
                    "Time does not map to an integer frame; adjacent legal frames are "
                            + lower + " and " + upper);
        }
    }

    /** Always displays the exact rational frame time, avoiding fake precision. */
    public static String formatFrame(long frame) {
        if (frame < 0L) {
            throw new IllegalArgumentException("Frame must be non-negative");
        }
        return frame + " / " + FPS + " s";
    }

    /** Maps full-width digits and separators typed by a Chinese IME to ASCII. */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + (c - '０')));
            } else if (c == '／') {
                out.append('/');
            } else if (c == '．') {
                out.append('.');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static long toLong(BigInteger value, String message) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(message, error);
        }
    }
}
