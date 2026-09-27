package org.telegram.owpengram;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministically and reversibly encodes an email address into a synthetic
 * "888"-prefixed phone-number-shaped string, so a self-hosted server with
 * TELESRV_EMAIL_SIGNUP_ENABLE=true can be reached through the exact same
 * auth.sendCode/signIn/signUp/account.changePhone flow a real phone number
 * uses, with no new TL constructors. Letters and digits pass through
 * unchanged; the handful of punctuation characters real email addresses use
 * are each replaced by a 2-character escape ('q' + a digit). Must stay
 * bit-for-bit identical to the Go server's internal/domain.EncodeEmailPhone
 * and the desktop client's Core::EncodeEmailSignupPhone.
 */
public class EmailSignupPhone {

    public static final String PREFIX = "888";
    private static final char ESCAPE = 'q';

    private static final Map<Character, Character> ENCODE_MAP = new HashMap<>();
    private static final Map<Character, Character> DECODE_MAP = new HashMap<>();

    static {
        ENCODE_MAP.put('@', '0');
        ENCODE_MAP.put('.', '1');
        ENCODE_MAP.put('-', '2');
        ENCODE_MAP.put('_', '3');
        ENCODE_MAP.put('+', '4');
        ENCODE_MAP.put(ESCAPE, '5');
        for (Map.Entry<Character, Character> e : ENCODE_MAP.entrySet()) {
            DECODE_MAP.put(e.getValue(), e.getKey());
        }
    }

    /**
     * Lowercases and trims an email so the same address always encodes to
     * the same synthetic phone number regardless of how it was typed.
     */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * Returns the encoded phone string, or null if email is empty/invalid,
     * contains a character outside [a-z0-9@._+-], or the result would not
     * fit the server's users.phone column (200 chars).
     */
    public static String encode(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty() || normalized.indexOf('@') < 0) {
            return null;
        }
        StringBuilder b = new StringBuilder(normalized.length() * 2);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 'a' && c <= 'z' && c != ESCAPE) {
                b.append(c);
            } else if (c >= '0' && c <= '9') {
                b.append(c);
            } else {
                Character digit = ENCODE_MAP.get(c);
                if (digit == null) {
                    return null;
                }
                b.append(ESCAPE).append(digit);
            }
        }
        String phone = PREFIX + b;
        if (phone.length() > 200) {
            return null;
        }
        return phone;
    }

    /** Reverses encode(). Returns null if phone isn't a validly-encoded email. */
    public static String decode(String phone) {
        if (phone == null) {
            return null;
        }
        String lower = phone.trim().toLowerCase();
        if (!lower.startsWith(PREFIX)) {
            return null;
        }
        String body = lower.substring(PREFIX.length());
        if (body.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != ESCAPE) {
                b.append(c);
                continue;
            }
            i++;
            if (i >= body.length()) {
                return null;
            }
            Character decoded = DECODE_MAP.get(body.charAt(i));
            if (decoded == null) {
                return null;
            }
            b.append(decoded);
        }
        String email = b.toString();
        if (email.isEmpty() || email.indexOf('@') < 0) {
            return null;
        }
        return email;
    }

    /**
     * Reports whether phone was produced by encode(). Every encoded value
     * contains at least one letter (the mandatory '@' escape's 'q' marker),
     * which real, all-digit phone numbers never do — even ones that happen
     * to start with the 888 area code — so this is unambiguous.
     */
    public static boolean isEmailSignupPhone(String phone) {
        if (phone == null) {
            return false;
        }
        String lower = phone.trim().toLowerCase();
        if (!lower.startsWith(PREFIX)) {
            return false;
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }
}
