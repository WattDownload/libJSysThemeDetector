module vip.zhifen.jsysthemedetector {
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires org.slf4j;
    requires jfa;
    requires com.github.oshi;
    requires org.jetbrains.annotations;
    requires org.semver4j;

    exports vip.zhifen.jsysthemedetector;

    // if JNA or others need deep reflection
    opens vip.zhifen.jsysthemedetector to com.sun.jna;
}