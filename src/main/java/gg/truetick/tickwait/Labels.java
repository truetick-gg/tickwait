package gg.truetick.tickwait;

/** Host/plan labels the server owner can attach to a shared report. */
final class Labels {
    static final int MAX_LENGTH = 60;

    private Labels() {
    }

    /** Strips colour codes, control and format characters, collapses spaces, caps the length. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++; // skip the colour code's argument too
                continue;
            }
            if (Character.getType(c) == Character.FORMAT) {
                continue; // bidi overrides, zero-width characters: they rearrange the page around them
            }
            if (Character.isISOControl(c)) {
                c = ' ';
            }
            if (c == ' ' && (sb.length() == 0 || sb.charAt(sb.length() - 1) == ' ')) {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString().trim();
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH).trim() : s;
    }
}
