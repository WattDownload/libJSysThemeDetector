package vip.zhifen.jsysthemedetector.util;

import org.semver4j.Semver;
import org.jetbrains.annotations.NotNull;
import oshi.PlatformEnum;
import oshi.SystemInfo;
import oshi.software.os.OperatingSystem;

public class OsInfo {

    private static final PlatformEnum platformType;
    private static final String family;
    private static final String version;

    static {
        final SystemInfo systemInfo = new SystemInfo();
        final OperatingSystem osInfo = systemInfo.getOperatingSystem();
        final OperatingSystem.OSVersionInfo osVersionInfo = osInfo.getVersionInfo();

        platformType = SystemInfo.getCurrentPlatform();
        family = osInfo.getFamily();
        version = osVersionInfo.getVersion();
    }

    public static boolean isWindows10OrLater() {
        return hasTypeAndVersionOrHigher(PlatformEnum.WINDOWS, "10");
    }

    public static boolean isLinux() {
        return hasType(PlatformEnum.LINUX);
    }

    public static boolean isMacOsMojaveOrLater() {
        return hasTypeAndVersionOrHigher(PlatformEnum.MACOS, "10.14");
    }

    @NotNull
    public static String getCurrentLinuxDesktopEnvironmentName() {
        return System.getenv("XDG_CURRENT_DESKTOP") != null ? System.getenv("XDG_CURRENT_DESKTOP") : "";
    }

    public static boolean isGnome() {
        return isLinux() && getCurrentLinuxDesktopEnvironmentName().toLowerCase().contains("gnome");
    }

    public static boolean isKde() {
        return isLinux() && getCurrentLinuxDesktopEnvironmentName().toLowerCase().contains("kde");
    }

    public static boolean hasType(PlatformEnum platformType) {
        return OsInfo.platformType.equals(platformType);
    }

    /**
     * CORRECTED LOGIC: Both the OS version and the target version string are
     * now converted to Semver objects using coerce() before comparison.
     */
    public static boolean isVersionAtLeast(String version) {
        // Coerce both version strings into valid Semver objects.
        Semver osVersion = Semver.coerce(OsInfo.version);
        Semver targetVersion = Semver.coerce(version);

        // If either string could not be parsed, comparison is not possible.
        if (osVersion == null || targetVersion == null) {
            return false;
        }

        // "is at least" means "is greater than" OR "is equal to".
        // Here we use the comparison methods that take another Semver object.
        return osVersion.isGreaterThan(targetVersion) || osVersion.isEqualTo(targetVersion);
    }

    public static boolean hasTypeAndVersionOrHigher(PlatformEnum platformType, String version) {
        return hasType(platformType) && isVersionAtLeast(version);
    }

    public static String getVersion() {
        return version;
    }

    public static String getFamily() {
        return family;
    }
}