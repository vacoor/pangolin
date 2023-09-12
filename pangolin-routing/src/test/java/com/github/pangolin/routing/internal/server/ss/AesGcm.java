package com.github.pangolin.routing.internal.server.ss;

import freework.util.Bytes;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class AesGcm {
    /***
     * aes-128-gcm 加密
     * @params msg 为加密信息 password为32位的16进制key
     * @return 返回base64编码，也可以返回16进制编码
     **/
    public static String Encrypt(byte[] sSrc, byte[] sKey) {
        try {
            SecretKeySpec skeySpec = new SecretKeySpec(sKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            //这边是获取一个随机的iv 默认为12位的
            byte[] iv = cipher.getIV();
            //执行加密
            byte[] encryptData = cipher.doFinal(sSrc);
            //这边进行拼凑 为 iv + 加密后的内容
            byte[] message = new byte[12 + sSrc.length + 16];
            System.arraycopy(iv, 0, message, 0, 12);
            System.arraycopy(encryptData, 0, message, 12, encryptData.length);

            return Base64.getEncoder().encodeToString(message);
        } catch (Exception ex) {
            return null;
        }
    }

    /***
     * aes-128-gcm 解密
     * @return msg 返回字符串
     */
    public static String Decrypt(String serect, byte[] sKey) {
        try {
            byte[] sSrc = Base64.getDecoder().decode(serect);

            GCMParameterSpec iv = new GCMParameterSpec(128, sSrc, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKey key2 = new SecretKeySpec(sKey, "AES");

            cipher.init(Cipher.DECRYPT_MODE, key2, iv);

            //这边和nodejs不同的一点是 不需要移除后面的16位
            byte[] decryptData = cipher.doFinal(sSrc, 12, sSrc.length - 12);

            return new String(decryptData);
        } catch (Exception ex) {
            return null;
        }
    }

    public static void main(String[] args) {
        String testMsg = "{'ai':'test-accountId','name':'username','idNum':'371321199012310912'}";
        String testPwd = "10210b07c5cf31b30f722f9b5896de5c";
        String enc = Encrypt(Bytes.toBytes(testMsg), Bytes.toBytes(testPwd));
        System.out.println("加密结果 " + testPwd);
        String dec = Decrypt(enc, Bytes.toBytes(testPwd));
        System.out.println("解密结果 " + dec);
    }
}