package com.mallcenter.app;

/**
 * 地址还原工具。
 *
 * 明文网址直接写在代码里，用反编译工具一搜就能看到；所以源码里存的是
 * 「密钥流逐字符异或」后的数字数组，运行时才还原成真正的网址。
 *
 * 密钥流由线性同余发生器推进（乘 1103515245 加 12345），
 * 每次取第 16~23 位与密文异或得到一个明文字符。
 */
final class Enc {

    private Enc() {
        // 纯工具类，不允许实例化
    }

    /**
     * 把加密数组还原成明文。
     *
     * @param data 加密后的字符数据
     * @param seed 密钥流种子（每个地址不同，防止一份密钥流被整体破解）
     */
    static String decode(int[] data, int seed) {
        StringBuilder sb = new StringBuilder(data.length);
        int k = seed;
        for (int i = 0; i < data.length; i++) {
            k = k * 1103515245 + 12345;              // 溢出即自然取模 2^32
            sb.append((char) (data[i] ^ ((k >>> 16) & 0xFF)));
        }
        return sb.toString();
    }
}
