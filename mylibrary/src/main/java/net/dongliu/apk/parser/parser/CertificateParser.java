package net.dongliu.apk.parser.parser;

import net.dongliu.apk.parser.ApkParsers;
import net.dongliu.apk.parser.bean.CertificateMeta;

import java.security.cert.CertificateException;
import java.util.List;

import androidx.annotation.NonNull;

/**
 * Parser certificate info.
 * One apk may have multi certificates(certificate chain).
 *
 * @author dongliu
 */
public abstract class CertificateParser {
    @NonNull
    protected final byte[] data;

    public CertificateParser(final @NonNull byte[] data) {
        this.data = data;
    }

    @NonNull
    public static CertificateParser getInstance(final @NonNull byte[] data) {
        if (ApkParsers.useBouncyCastle()) {
            return new BCCertificateParser(data);
        }
        return new JSSECertificateParser(data);
    }

    /**
     * get certificate info
     */
    @NonNull
    public abstract List<CertificateMeta> parse() throws CertificateException;

}
