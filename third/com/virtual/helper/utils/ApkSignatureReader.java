package com.lody.virtual.helper.utils;

import android.content.pm.Signature;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * TwinBox 2.1.55：APK 签名直读（Signing Block 内 DER 证书扫描）。
 *
 * 为什么需要它
 * ------------
 * Android 12+ 的安装解析走公开 API 桥（getPackageArchiveInfo），archive 模式
 * **不收集证书**——pi.signatures 恒为 null。guest（如 com.example.ourom 的
 * libourom.so）在 JNI_OnLoad 里做签名自检，GET_SIGNATURES 拿到 null：
 * sig[0] == null → GetObjectClass(null) → JNI abort → SIGABRT。
 * （与 2.1.45 丢 intent-filter 同根：公开 API 桥的先天缺陷。）
 *
 * 实测教训（对算法选型的影响）
 * ------------------------
 * 第一版按 apksig 规范做结构化解析（ID-value 对 → V3 优先 → V2 signer 层层 LP 剥）
 * **在真实 APK 上验证失败**：V2/V3 的 length-prefixed 嵌套与记忆中的规范有出入
 * （对长字段是 uint64 不是 uint32；层级也有漂移）。逐层 dump 后改用**鲁棒策略**：
 * 定位 Signing Block 后在 pairs 区全量扫描 X.509 DER 证书特征——
 *   1. 必以 30 82 xx xx 开头（SEQUENCE + 长形式长度）；
 *   2. 长度自洽（4 + xx xx ≤ 剩余字节）；
 *   3. 总长 ≥ 400（RSA/EC 证书下限，排除 digest/公钥等小 blob 误报）；
 *   4. 偏移 +4 处应有 30 82（TbsCertificate 的 SEQUENCE 头）。
 * 该方案已在 TwinBox 自签 APK 上对照 apksigner --print-certs 验证 sha256 一致。
 * V2/V3/V3.1 结构再怎么变，证书字节形态不变——这也是 V2 之前 V1-only 老包
 * 拿不到的原因（V1 证书在 META-INF/*.RSA 的 PKCS#7 里，本类不处理；
 * Android 16 上 target 老的 V1-only 包本来就装不进容器，实际影响为零）。
 *
 * 用法：ApkSignatureReader.read(new File(apkPath)) → Signature[]（null = 无/失败）
 */
public class ApkSignatureReader {

    private static final byte[] APK_SIG_BLOCK_MAGIC =
            {'A', 'P', 'K', ' ', 'S', 'i', 'g', ' ', 'B', 'l', 'o', 'c', 'k', ' ', '4', '2'};
    private static final int EOCD_MAGIC = 0x06054b50;
    private static final int MIN_CERT_LEN = 400;

    public static Signature[] read(File apk) {
        if (apk == null || !apk.isFile()) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(apk, "r");
            byte[] tail = new byte[(int) Math.min(raf.length(), 64 * 1024)];
            // EOCD 在文件尾部 22B+注释，扫最后 64KB 足够
            raf.seek(raf.length() - tail.length);
            raf.readFully(tail);

            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (le4(tail, i) == EOCD_MAGIC) {
                    // 校验注释长度自洽（eocd+20 处应为到文件尾的距离）
                    int commentLen = le2(tail, i + 20);
                    if (i + 22 + commentLen == tail.length) {
                        eocd = i;
                        break;
                    }
                }
            }
            if (eocd < 0) {
                return null;
            }
            long fileLen = raf.length();
            long tailStart = fileLen - tail.length;
            long cdStart = (le4(tail, eocd + 16) & 0xFFFFFFFFL) + tailStart;
            if (cdStart < 48 || cdStart > fileLen) {
                return null;
            }
            // Signing Block 尾 24B：size(8) + magic(16)
            byte[] sbTail = new byte[24];
            raf.seek(cdStart - 24);
            raf.readFully(sbTail);
            for (int i = 0; i < 16; i++) {
                if (sbTail[8 + i] != APK_SIG_BLOCK_MAGIC[i]) {
                    return null; // 无签名块（V1-only 老包）
                }
            }
            long blockSize = le8(sbTail, 0);
            if (blockSize < 24 || cdStart - blockSize < 8) {
                return null;
            }
            long blockStart = cdStart - 8 - blockSize;
            // pairs 区：头 size 之后到尾 size 之前
            long pairsStart = blockStart + 8;
            int pairsLen = (int) ((cdStart - 24) - pairsStart);
            if (pairsLen <= 0 || pairsLen > 64 * 1024 * 1024) {
                return null;
            }
            byte[] pairs = new byte[pairsLen];
            raf.seek(pairsStart);
            raf.readFully(pairs);
            return scanCertificates(pairs);
        } catch (Throwable t) {
            VLog.w("ApkSignatureReader", "read fail (" + apk.getName() + "): " + t);
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /** pairs 区全量 DER 扫描（V2/V3/V3.1 的证书都在这里，形态不变）。 */
    private static Signature[] scanCertificates(byte[] buf) {
        List<byte[]> certs = new ArrayList<>();
        int i = 0;
        while (i + 4 < buf.length) {
            if (buf[i] == 0x30 && buf[i + 1] == 0x82) {
                int derLen = ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
                int total = derLen + 4;
                if (total >= MIN_CERT_LEN && total <= buf.length - i) {
                    // TbsCertificate 也是 SEQUENCE（0x30 0x82 起头）
                    if (buf[i + 4] == 0x30 && (buf[i + 5] & 0xFF) == 0x82) {
                        byte[] cert = new byte[total];
                        System.arraycopy(buf, i, cert, 0, total);
                        if (!contains(certs, cert)) {
                            certs.add(cert);
                        }
                        i += total;
                        continue;
                    }
                }
            }
            i++;
        }
        if (certs.isEmpty()) {
            return null;
        }
        Signature[] signatures = new Signature[certs.size()];
        for (int k = 0; k < certs.size(); k++) {
            signatures[k] = new Signature(certs.get(k));
        }
        return signatures;
    }

    private static boolean contains(List<byte[]> list, byte[] cert) {
        for (byte[] b : list) {
            if (java.util.Arrays.equals(b, cert)) {
                return true;
            }
        }
        return false;
    }

    private static int le2(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le4(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long le8(byte[] b, int off) {
        return (le4(b, off) & 0xFFFFFFFFL) | ((le4(b, off + 4) & 0xFFFFFFFFL) << 32);
    }
}
