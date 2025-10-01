/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package vip.zhifen.jsysthemedetector;

import vip.zhifen.jsysthemedetector.util.ConcurrentHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Used for detecting the dark theme on a Linux (GNOME/GTK) system.
 * Tested on Ubuntu.
 *
 * @author Daniel Gyorffy
 */
class GnomeThemeDetector extends OsThemeDetector {

    private static final Logger logger = LoggerFactory.getLogger(GnomeThemeDetector.class);

    private static final String[] MONITORING_CMD = {
            "gsettings", "monitor", "org.gnome.desktop.interface"
    };
    private static final String[][] GET_CMD = new String[][]{
            {"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"},
            {"gsettings", "get", "org.gnome.desktop.interface", "color-scheme"}
    };

    private final Set<Consumer<Boolean>> listeners = new ConcurrentHashSet<>();
    private final Pattern darkThemeNamePattern = Pattern.compile(".*dark.*", Pattern.CASE_INSENSITIVE);

    private volatile DetectorThread detectorThread;

    @Override
    public boolean isDark() {
        for (String[] cmd : GET_CMD) {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            try {
                Process process = builder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();

                    int exitCode = process.waitFor(); // ✅ wait for process to finish
                    if (exitCode != 0) {
                        logger.warn("Theme detection command '{}' exited with code {}", String.join(" ", cmd), exitCode);
                    }

                    if (line != null && isDarkTheme(line)) {
                        return true;
                    }
                }
            } catch (IOException | InterruptedException e) {
                logger.error("Couldn't detect Linux OS theme", e);
                Thread.currentThread().interrupt(); // Important if interrupted
            }
        }
        return false;
    }


    private boolean isDarkTheme(String gtkTheme) {
        return darkThemeNamePattern.matcher(gtkTheme).matches();
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public synchronized void registerListener(@NotNull Consumer<Boolean> darkThemeListener) {
        Objects.requireNonNull(darkThemeListener);
        final boolean listenerAdded = listeners.add(darkThemeListener);
        final boolean singleListener = listenerAdded && listeners.size() == 1;
        final DetectorThread currentDetectorThread = detectorThread;
        final boolean threadInterrupted = currentDetectorThread != null && currentDetectorThread.isInterrupted();

        if (singleListener || threadInterrupted) {
            final DetectorThread newDetectorThread = new DetectorThread(this);
            this.detectorThread = newDetectorThread;
            newDetectorThread.start();
        }
    }

    @Override
    public synchronized void removeListener(@Nullable Consumer<Boolean> darkThemeListener) {
        listeners.remove(darkThemeListener);
        if (listeners.isEmpty()) {
            this.detectorThread.interrupt();
            this.detectorThread = null;
        }
    }

    /**
     * Thread implementation for detecting the actually changed theme
     */
    private static final class DetectorThread extends Thread {

        private final GnomeThemeDetector detector;
        private final Pattern outputPattern = Pattern.compile("(gtk-theme|color-scheme).*", Pattern.CASE_INSENSITIVE);
        private boolean lastValue;

        DetectorThread(@NotNull GnomeThemeDetector detector) {
            this.detector = detector;
            this.lastValue = detector.isDark();
            this.setName("GTK Theme Detector Thread");
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            ProcessBuilder builder = new ProcessBuilder(MONITORING_CMD);
            builder.redirectErrorStream(true);
            try {
                Process monitoringProcess = builder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(monitoringProcess.getInputStream()))) {
                    while (!this.isInterrupted()) {
                        String readLine = reader.readLine();

                        // reader.readLine sometimes returns null on application shutdown.
                        if (readLine == null) {
                            continue;
                        }

                        if (!outputPattern.matcher(readLine).matches()) {
                            continue;
                        }

                        String[] keyValue = readLine.split("\\s");
                        if (keyValue.length < 2) continue;

                        String value = keyValue[1];
                        boolean currentDetection = detector.isDarkTheme(value);
                        logger.debug("Theme changed detection, dark: {}", currentDetection);

                        if (currentDetection != lastValue) {
                            lastValue = currentDetection;
                            for (Consumer<Boolean> listener : detector.listeners) {
                                try {
                                    listener.accept(currentDetection);
                                } catch (RuntimeException e) {
                                    logger.error("Caught exception during listener notifying", e);
                                }
                            }
                        }
                    }

                } finally {
                    logger.debug("ThemeDetectorThread has been interrupted!");
                    if (monitoringProcess.isAlive()) {
                        monitoringProcess.destroy();

                        try {
                            boolean exited = monitoringProcess.waitFor(5, TimeUnit.SECONDS);
                            if (!exited) {
                                logger.warn("Monitoring process did not exit in time, killing forcibly...");
                                monitoringProcess.destroyForcibly();
                                monitoringProcess.waitFor(); // Ensure final cleanup
                            }
                        } catch (InterruptedException e) {
                            logger.warn("Interrupted while waiting for process to terminate", e);
                            Thread.currentThread().interrupt();
                        }

                        logger.debug("Monitoring process has been terminated and reaped.");
                    }
                }
            } catch (IOException e) {
                logger.error("Couldn't start monitoring process", e);
            } catch (ArrayIndexOutOfBoundsException e) {
                logger.error("Couldn't parse command line output", e);
            }
        }
    }
}
