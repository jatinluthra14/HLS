package com.jtb.hls;

import android.net.Uri;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Util {
    public static String clearQuery(String url) {
        Uri uri = Uri.parse(url.trim());
        return uri.buildUpon().clearQuery().build().toString();
    }

    public static int indexOf(byte[] outerArray, byte[] smallerArray) {
        for(int i = 0; i < outerArray.length - smallerArray.length+1; ++i) {
            boolean found = true;
            for(int j = 0; j < smallerArray.length; ++j) {
                if (outerArray[i+j] != smallerArray[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    public static String processM3U8Url(String line, String orig_url) {
        String final_url = line.trim();
        if (!(final_url.startsWith("http"))) {
            if (final_url.startsWith("/")) {
                final_url = Uri.parse(orig_url).getHost() + final_url;
            } else {
                String strip_orig = Util.clearQuery(orig_url);
                String[] segments = strip_orig.split("/");
                segments[segments.length - 1] = final_url;
                final_url = String.join("/", segments);
            }
        }
        return final_url;
    }

    public static String getMD5EncryptedString(String encTarget){
        MessageDigest mdEnc = null;
        try {
            mdEnc = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("Exception while encrypting to md5");
            e.printStackTrace();
        } // Encryption algorithm
        mdEnc.update(encTarget.getBytes(), 0, encTarget.length());
        String md5 = new BigInteger(1, mdEnc.digest()).toString(16);
        while ( md5.length() < 32 ) {
            md5 = "0" + md5;
        }
        return md5;
    }
}
