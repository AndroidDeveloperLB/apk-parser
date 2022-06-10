package net.dongliu.apk.parser.bean;


import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Apk meta info
 *
 * @author dongliu
 */
public class ApkMeta {

    private final String packageName;
    private final String label;
    private final String icon;
    private final String versionName;
    private final Long versionCode;
    private final Long revisionCode;
    private final String sharedUserId;
    private final String sharedUserLabel;
    private final String split;
    private final String configForSplit;
    private final boolean isFeatureSplit;
    private final boolean isSplitRequired;
    private final boolean isolatedSplits;
    private final String installLocation;
    private final String minSdkVersion;
    private final String targetSdkVersion;
    @Nullable
    private final String maxSdkVersion;
    @Nullable
    private final String compileSdkVersion;
    @Nullable
    private final String compileSdkVersionCodename;
    @Nullable
    private final String platformBuildVersionCode;
    @Nullable
    private final String platformBuildVersionName;
    private final GlEsVersion glEsVersion;
    private final boolean anyDensity;
    private final boolean smallScreens;
    private final boolean normalScreens;
    private final boolean largeScreens;
    private final boolean debuggable;

    private final List<String> usesPermissions;
    private final List<UseFeature> usesFeatures;
    private final List<Permission> permissions;

    private ApkMeta(final Builder builder) {
        this.packageName = builder.packageName;
        this.label = builder.label;
        this.icon = builder.icon;
        this.versionName = builder.versionName;
        this.versionCode = builder.versionCode;
        this.revisionCode = builder.revisionCode;
        this.sharedUserId = builder.sharedUserId;
        this.sharedUserLabel = builder.sharedUserLabel;
        this.split = builder.split;
        this.configForSplit = builder.configForSplit;
        this.isFeatureSplit = builder.isFeatureSplit;
        this.isSplitRequired = builder.isSplitRequired;
        this.isolatedSplits = builder.isolatedSplits;
        this.installLocation = builder.installLocation;
        this.minSdkVersion = builder.minSdkVersion;
        this.targetSdkVersion = builder.targetSdkVersion;
        this.maxSdkVersion = builder.maxSdkVersion;
        this.compileSdkVersion = builder.compileSdkVersion;
        this.compileSdkVersionCodename = builder.compileSdkVersionCodename;
        this.platformBuildVersionCode = builder.platformBuildVersionCode;
        this.platformBuildVersionName = builder.platformBuildVersionName;
        this.glEsVersion = builder.glEsVersion;
        this.anyDensity = builder.anyDensity;
        this.smallScreens = builder.smallScreens;
        this.normalScreens = builder.normalScreens;
        this.largeScreens = builder.largeScreens;
        this.debuggable = builder.debuggable;
        this.usesPermissions = builder.usesPermissions;
        this.usesFeatures = builder.usesFeatures;
        this.permissions = builder.permissions;
    }

    @NonNull
    public static Builder newBuilder() {
        return new Builder();
    }

    public String getPackageName() {
        return this.packageName;
    }

    public String getVersionName() {
        return this.versionName;
    }

    public Long getVersionCode() {
        return this.versionCode;
    }

    public Long getRevisionCode() {
        return this.revisionCode;
    }

    public String getSharedUserId() {
        return this.sharedUserId;
    }

    public String getSharedUserLabel() {
        return this.sharedUserLabel;
    }

    public String getSplit() {
        return this.split;
    }

    public String getConfigForSplit() {
        return this.configForSplit;
    }

    public boolean isFeatureSplit() {
        return this.isFeatureSplit;
    }

    public boolean isSplitRequired() {
        return this.isSplitRequired;
    }

    public boolean isIsolatedSplits() {
        return this.isolatedSplits;
    }

    public String getMinSdkVersion() {
        return this.minSdkVersion;
    }

    public String getTargetSdkVersion() {
        return this.targetSdkVersion;
    }

    @Nullable
    public String getMaxSdkVersion() {
        return this.maxSdkVersion;
    }

    @Nullable
    public String getCompileSdkVersion() {
        return this.compileSdkVersion;
    }

    @Nullable
    public String getCompileSdkVersionCodename() {
        return this.compileSdkVersionCodename;
    }

    @Nullable
    public String getPlatformBuildVersionCode() {
        return this.platformBuildVersionCode;
    }

    @Nullable
    public String getPlatformBuildVersionName() {
        return this.platformBuildVersionName;
    }

    public List<String> getUsesPermissions() {
        return this.usesPermissions;
    }

    public void addUsesPermission(final String permission) {
        this.usesPermissions.add(permission);
    }

    /**
     * alias for getLabel
     */
    public String getName() {
        return this.label;
    }

    /**
     * get the apk's title(name)
     */
    public String getLabel() {
        return this.label;
    }

    public boolean isAnyDensity() {
        return this.anyDensity;
    }

    public boolean isSmallScreens() {
        return this.smallScreens;
    }

    public boolean isNormalScreens() {
        return this.normalScreens;
    }

    public boolean isLargeScreens() {
        return this.largeScreens;
    }

    public boolean isDebuggable() {
        return this.debuggable;
    }

    public GlEsVersion getGlEsVersion() {
        return this.glEsVersion;
    }

    public List<UseFeature> getUsesFeatures() {
        return this.usesFeatures;
    }

    public void addUseFeatures(final UseFeature useFeature) {
        this.usesFeatures.add(useFeature);
    }

    public String getInstallLocation() {
        return this.installLocation;
    }

    public void addPermission(final Permission permission) {
        this.permissions.add(permission);
    }

    public List<Permission> getPermissions() {
        return this.permissions;
    }

    @NonNull
    @Override
    public String toString() {
        return "packageName: \t" + this.packageName + "\n"
                + "label: \t" + this.label + "\n"
                + "icon: \t" + this.icon + "\n"
                + "versionName: \t" + this.versionName + "\n"
                + "versionCode: \t" + this.versionCode + "\n"
                + "minSdkVersion: \t" + this.minSdkVersion + "\n"
                + "targetSdkVersion: \t" + this.targetSdkVersion + "\n"
                + "maxSdkVersion: \t" + this.maxSdkVersion;
    }

    public static final class Builder {
        private String packageName;
        private String label;
        private String icon;
        private String versionName;
        private Long versionCode;
        private Long revisionCode;
        private String sharedUserId;
        private String sharedUserLabel;
        public String split;
        public String configForSplit;
        public boolean isFeatureSplit;
        public boolean isSplitRequired;
        public boolean isolatedSplits;
        private String installLocation;
        private String minSdkVersion;
        private String targetSdkVersion;
        private String maxSdkVersion;
        private String compileSdkVersion;
        private String compileSdkVersionCodename;
        private String platformBuildVersionCode;
        private String platformBuildVersionName;
        private GlEsVersion glEsVersion;
        private boolean anyDensity;
        private boolean smallScreens;
        private boolean normalScreens;
        private boolean largeScreens;
        private boolean debuggable;
        private final List<String> usesPermissions = new ArrayList<>();
        private final List<UseFeature> usesFeatures = new ArrayList<>();
        private final List<Permission> permissions = new ArrayList<>();

        private Builder() {
        }

        @NonNull
        public Builder setPackageName(final @Nullable String packageName) {
            this.packageName = packageName;
            return this;
        }

        @NonNull
        public Builder setLabel(final @NonNull String label) {
            this.label = label;
            return this;
        }

        @NonNull
        public Builder setIcon(final @Nullable String icon) {
            this.icon = icon;
            return this;
        }

        @NonNull
        public Builder setVersionName(final @Nullable String versionName) {
            this.versionName = versionName;
            return this;
        }

        @NonNull
        public Builder setVersionCode(final @Nullable Long versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        @NonNull
        public Builder setRevisionCode(final @Nullable Long revisionCode) {
            this.revisionCode = revisionCode;
            return this;
        }

        @NonNull
        public Builder setSharedUserId(final @Nullable String sharedUserId) {
            this.sharedUserId = sharedUserId;
            return this;
        }

        @NonNull
        public Builder setSharedUserLabel(final @Nullable String sharedUserLabel) {
            this.sharedUserLabel = sharedUserLabel;
            return this;
        }

        @NonNull
        public Builder setSplit(final @Nullable String split) {
            this.split = split;
            return this;
        }

        @NonNull
        public Builder setConfigForSplit(final @Nullable String configForSplit) {
            this.configForSplit = configForSplit;
            return this;
        }

        @NonNull
        public Builder setIsFeatureSplit(final boolean isFeatureSplit) {
            this.isFeatureSplit = isFeatureSplit;
            return this;
        }

        @NonNull
        public Builder setIsSplitRequired(final boolean isSplitRequired) {
            this.isSplitRequired = isSplitRequired;
            return this;
        }

        @NonNull
        public Builder setIsolatedSplits(final boolean isolatedSplits) {
            this.isolatedSplits = isolatedSplits;
            return this;
        }

        @NonNull
        public Builder setInstallLocation(final @NonNull String installLocation) {
            this.installLocation = installLocation;
            return this;
        }

        @NonNull
        public Builder setMinSdkVersion(final @NonNull String minSdkVersion) {
            this.minSdkVersion = minSdkVersion;
            return this;
        }

        @NonNull
        public Builder setTargetSdkVersion(final @NonNull String targetSdkVersion) {
            this.targetSdkVersion = targetSdkVersion;
            return this;
        }

        @NonNull
        public Builder setMaxSdkVersion(final @NonNull String maxSdkVersion) {
            this.maxSdkVersion = maxSdkVersion;
            return this;
        }

        @NonNull
        public Builder setCompileSdkVersion(final @Nullable String compileSdkVersion) {
            this.compileSdkVersion = compileSdkVersion;
            return this;
        }

        @NonNull
        public Builder setCompileSdkVersionCodename(final @Nullable String compileSdkVersionCodename) {
            this.compileSdkVersionCodename = compileSdkVersionCodename;
            return this;
        }

        @NonNull
        public Builder setPlatformBuildVersionCode(final @Nullable String platformBuildVersionCode) {
            this.platformBuildVersionCode = platformBuildVersionCode;
            return this;
        }

        @NonNull
        public Builder setPlatformBuildVersionName(final @Nullable String platformBuildVersionName) {
            this.platformBuildVersionName = platformBuildVersionName;
            return this;
        }

        @NonNull
        public Builder setGlEsVersion(final @NonNull GlEsVersion glEsVersion) {
            this.glEsVersion = glEsVersion;
            return this;
        }

        @NonNull
        public Builder setAnyDensity(final boolean anyDensity) {
            this.anyDensity = anyDensity;
            return this;
        }

        @NonNull
        public Builder setSmallScreens(final boolean smallScreens) {
            this.smallScreens = smallScreens;
            return this;
        }

        @NonNull
        public Builder setNormalScreens(final boolean normalScreens) {
            this.normalScreens = normalScreens;
            return this;
        }

        @NonNull
        public Builder setLargeScreens(final boolean largeScreens) {
            this.largeScreens = largeScreens;
            return this;
        }

        @NonNull
        public Builder setDebuggable(final boolean debuggable) {
            this.debuggable = debuggable;
            return this;
        }

        @NonNull
        public Builder addUsesPermission(final @Nullable String usesPermission) {
            this.usesPermissions.add(usesPermission);
            return this;
        }

        @NonNull
        public Builder addUsesFeature(final @NonNull UseFeature usesFeature) {
            this.usesFeatures.add(usesFeature);
            return this;
        }

        @NonNull
        public Builder addPermissions(final @NonNull Permission permission) {
            this.permissions.add(permission);
            return this;
        }

        @NonNull
        public ApkMeta build() {
            return new ApkMeta(this);
        }
    }
}