package net.dongliu.apk.parser.bean;

import java.util.List;

import androidx.annotation.NonNull;

/**
 * ApkSignV1 certificate file.
 */
public class ApkV2Signer {
    /**
     * The meta info of certificate contained in this cert file.
     */
    @NonNull
    private final List<CertificateMeta> certificateMetas;

    public ApkV2Signer(final @NonNull List<CertificateMeta> certificateMetas) {
        this.certificateMetas = certificateMetas;
    }

    @NonNull
    public List<CertificateMeta> getCertificateMetas() {
        return this.certificateMetas;
    }

}
