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
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Used for detecting the dark theme on a Linux KDE desktop environment.
 * Tested on Ubuntu KDE Plasma (kde-plasma-desktop).
 *
 * @author Thomas Sartre
 * @see GnomeThemeDetector
 */
public class KdeThemeDetector extends OsThemeDetector {

    private static final Logger logger = LoggerFactory.getLogger(KdeThemeDetector.class);

    private static final String[] GET_THEME_CMD = {
            "kreadconfig5", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"
    };

    private final Set<Consumer<Boolean>> listeners = new ConcurrentHashSet<>();
    private final Pattern darkThemeNamePattern = Pattern.compile(".*dark.*", Pattern.CASE_INSENSITIVE);

    private volatile DetectorThread detectorThread;

    @Override
    public boolean isDark() {
        ProcessBuilder builder = new ProcessBuilder(GET_THEME_CMD);
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String theme = reader.readLine();

                int exitCode = process.waitFor(); // ✅ wait for completion
                if (exitCode != 0) {
                    logger.warn("Theme detection command exited with code {}", exitCode);
                }

                if (theme != null && isDarkTheme(theme)) {
                    return true;
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Couldn't detect KDE OS theme", e);
            Thread.currentThread().interrupt(); // ✅ restore interrupt status
        }

        return false;
    }


    private boolean isDarkTheme(String theme) {
        return darkThemeNamePattern.matcher(theme).matches();
    }

    @Override
    public synchronized void registerListener(@NotNull Consumer<Boolean> darkThemeListener) {
        Objects.requireNonNull(darkThemeListener);
        boolean listenerAdded = listeners.add(darkThemeListener);
        boolean singleListener = listenerAdded && listeners.size() == 1;

        if (singleListener || (detectorThread != null && detectorThread.isInterrupted())) {
            detectorThread = new DetectorThread(this);
            detectorThread.start();
        }
    }

    @Override
    public synchronized void removeListener(@Nullable Consumer<Boolean> darkThemeListener) {
        listeners.remove(darkThemeListener);
        if (listeners.isEmpty() && detectorThread != null) {
            detectorThread.interrupt();
            detectorThread = null;
        }
    }

    /**
     * Thread implementation for detecting the actually changed theme.
     */
    private static final class DetectorThread extends Thread {

        private final KdeThemeDetector detector;
        private boolean lastValue;

        DetectorThread(@NotNull KdeThemeDetector detector) {
            this.detector = detector;
            this.lastValue = detector.isDark();
            this.setName("KDE Theme Detector Thread");
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            while (!this.isInterrupted()) {
                boolean currentDetection = detector.isDark();
                if (currentDetection != lastValue) {
                    lastValue = currentDetection;
                    for (Consumer<Boolean> listener : detector.listeners) {
                        try {
                            listener.accept(currentDetection);
                        } catch (RuntimeException e) {
                            logger.error("Caught exception during listener notification", e);
                        }
                    }
                }
            }
        }
    }
}
