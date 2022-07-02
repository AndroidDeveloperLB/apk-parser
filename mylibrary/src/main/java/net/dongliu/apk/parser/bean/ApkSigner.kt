package net.dongliu.apk.parser.bean;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * ApkSignV1 certificate file.
 */
public class ApkSigner {
    /**
     * The cert file path in apk file
     */
    public final String path;
    /**
     * The meta info of certificate contained in this cert file.
     */
    @NonNull
    public final List<CertificateMeta> certificateMetas;

    public ApkSigner(@NonNull final String path, final @NonNull List<CertificateMeta> certificateMetas) {
        this.path = path;
        this.certificateMetas = certificateMetas;
    }

    @NonNull
    @Override
    public String toString() {
        return "ApkSigner{" +
                "path='" + this.path + '\'' +
                ", certificateMetas=" + this.certificateMetas +
                '}';
    }
}
