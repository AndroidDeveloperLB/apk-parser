package net.dongliu.apk.parser.struct.resource;

/**
 * used by resource Type.
 *
 * @author dongliu
 */
public class ResTableConfig {
    private int size;
    private short mcc;
    private short mnc;
    private String language;
    private String country;
    private String script;
    private String variant;
    private byte orientation;
    private byte touchscreen;
    private short density;
    private short keyboard;
    private short navigation;
    private short inputFlags;
    private int screenWidth;
    private int screenHeight;
    private int sdkVersion;
    private int minorVersion;
    private short screenLayout;
    private short uiMode;
    private short screenLayout2;
    private byte colorMode;
    private byte screenConfigPad2;

    public String getLanguage() {
        return this.language;
    }

    public void setLanguage(final String language) {
        this.language = language;
    }

    public String getCountry() {
        return this.country;
    }

    public void setCountry(final String country) {
        this.country = country;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public int getDensity() {
        return this.density & 0xffff;
    }

    public void setDensity(final int density) {
        this.density = (short) density;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public void setMcc(short mcc) {
        this.mcc = mcc;
    }

    public void setMnc(short mnc) {
        this.mnc = mnc;
    }

    public void setOrientation(short orientation) {
        this.orientation = (byte) orientation;
    }

    public void setTouchscreen(short touchscreen) {
        this.touchscreen = (byte) touchscreen;
    }

    public int getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(int sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public void setKeyboard(short keyboard) { this.keyboard = keyboard; }
    public void setNavigation(short navigation) { this.navigation = navigation; }
    public void setInputFlags(short inputFlags) { this.inputFlags = inputFlags; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }
    public void setMinorVersion(int minorVersion) { this.minorVersion = minorVersion; }
    public void setScreenLayout(short screenLayout) { this.screenLayout = screenLayout; }
    public void setUiMode(short uiMode) { this.uiMode = uiMode; }
    public void setScreenLayout2(short screenLayout2) { this.screenLayout2 = screenLayout2; }
    public void setColorMode(byte colorMode) { this.colorMode = colorMode; }
    public void setScreenConfigPad2(byte screenConfigPad2) { this.screenConfigPad2 = screenConfigPad2; }
}
