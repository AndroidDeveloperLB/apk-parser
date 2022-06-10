package net.dongliu.apk.parser.bean;

import java.util.List;

import androidx.annotation.NonNull;

/**
 * ApkSignV1 certificate file.
 */
public class ApkSigner {
    /**
     * The cert file path in apk file
     */
    private final String path;
    /**
     * The meta info of certificate contained in this cert file.
     */
    @NonNull
    private final List<CertificateMeta> certificateMetas;

    public ApkSigner(@NonNull final String path, final @NonNull List<CertificateMeta> certificateMetas) {
        this.path = path;
        this.certificateMetas = certificateMetas;
    }

    public String getPath() {
        return this.path;
    }

    @NonNull
    public List<CertificateMeta> getCertificateMetas() {
        return this.certificateMetas;
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
