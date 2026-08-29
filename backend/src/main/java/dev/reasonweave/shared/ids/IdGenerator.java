package dev.reasonweave.shared.ids;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class IdGenerator {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next(String prefix) {
        byte[] bytes = new byte[16];
        long timestamp = System.currentTimeMillis();
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;
        byte[] entropy = new byte[10];
        random.nextBytes(entropy);
        System.arraycopy(entropy, 0, bytes, 6, entropy.length);
        return prefix + "_" + encode(bytes);
    }

    private String encode(byte[] bytes) {
        StringBuilder output = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                output.append(CROCKFORD[(buffer >>> bits) & 31]);
            }
        }
        if (bits > 0) {
            output.append(CROCKFORD[(buffer << (5 - bits)) & 31]);
        }
        while (output.length() < 26) {
            output.insert(0, '0');
        }
        return output.substring(0, 26);
    }
}
