package net.dongliu.apk.parser.bean

/**
 * the glEsVersion apk used.
 * 
 * @author dongliu
 */
class GlEsVersion(val major: Int, val minor: Int, val isRequired: Boolean) {
    override fun toString(): String {
        return this.major.toString() + "." + this.minor
    }
}
