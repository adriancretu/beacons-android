package com.uriio.beacons.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class EddystoneURLTest {
    // java.lang.RuntimeException: Method put in android.util.ArrayMap not mocked
    private Map<String, byte[]> valid = new HashMap<>();

    private List<byte[]> undecodable = new ArrayList<>();
    private List<String> unencodable = new ArrayList<>();

    // tests strings that are not optimally compressed but still valid
    private Map<String, byte[]> unexpanded = new HashMap<>();

    {
        valid.put(null, null);

        valid.put("http://www.linkedin.com/", new byte[]{0x00, 'l', 'i', 'n', 'k', 'e', 'd', 'i', 'n', 0x00});
        valid.put("https://www.google.com/", new byte[]{0x01, 'g', 'o', 'o', 'g', 'l', 'e', 0x00});
        valid.put("http://twitter.com/", new byte[]{0x02, 't', 'w', 'i', 't', 't', 'e', 'r', 0x00});
        valid.put("https://example.com/", new byte[]{0x03, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x00});
        valid.put("http://localhost", new byte[]{0x02, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'});
        valid.put("https://abc.xyz", new byte[]{0x03, 'a', 'b', 'c', '.', 'x', 'y', 'z'});
        valid.put("https://youtu.be/?v=4O4", new byte[]{0x03, 'y', 'o', 'u', 't', 'u', '.', 'b', 'e', '/', '?', 'v', '=', '4', 'O', '4'});

        valid.put("https://.com", new byte[]{0x03, 0x07});
        valid.put("http://", new byte[]{0x02});

        valid.put("http://.com.edu.org.net/", new byte[] { 0x02, 0x07, 0x09, 0x08, 0x03});
        valid.put("http://x.info/.educa.netty", new byte[] { 0x02, 'x', 0x04, 0x09, 'c', 'a', 0x0a, 't', 'y'});

        undecodable.add(new byte[] {(byte) 0xff});
        undecodable.add(new byte[] {0x00, (byte) 0xff});
        undecodable.add("x".getBytes());
        undecodable.add(new byte[]{0x05, 'x'});

        unencodable.add("maybeNextTime");
        unencodable.add("http://\u0128");

        unexpanded.put("http://x.com", new byte[] {0x02, 'x', '.', 'c', 'o', 'm'});
    }

    @Test
    public void decode() throws Exception {
        for (Map.Entry<String, byte[]> entry : valid.entrySet()) {
            assertEquals(entry.getKey(), EddystoneURL.decode(entry.getValue()));
        }

        for (Map.Entry<String, byte[]> entry : unexpanded.entrySet()) {
            assertEquals(entry.getKey(), EddystoneURL.decode(entry.getValue()));
        }

        for (byte[] bytes : undecodable) {
            assertEquals(null, EddystoneURL.decode(bytes));
        }
    }

    @Test
    public void encode() throws Exception {
        for (Map.Entry<String, byte[]> entry : valid.entrySet()) {
            assertArrayEquals(entry.getKey(), EddystoneURL.encode(entry.getKey()), entry.getValue());
        }

        for (Map.Entry<String, byte[]> entry : unexpanded.entrySet()) {
            // we expect this item to compress better than original
            assertNotEquals(entry.getKey(), EddystoneURL.encode(entry.getKey()));
        }

        for (String value : unencodable) {
//            System.out.println(value);
            assertNull(value, EddystoneURL.encode(value));
        }
    }
}