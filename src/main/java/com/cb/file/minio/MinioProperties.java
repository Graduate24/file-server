

package com.cb.file.minio;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Identifies and stores version information of minio-java package at run time.
 */
enum MinioProperties {
    INSTANCE;

    private static final Logger LOGGER = Logger.getLogger(MinioProperties.class.getName());

    private final AtomicReference<String> version = new AtomicReference<>(null);

    public String getVersion() {
        String result = version.get();
        if (result == null) {
            synchronized (INSTANCE) {
                if (version.get() == null) {
                    try {
                        ClassLoader classLoader = getClass().getClassLoader();
                        setMinioClientJavaVersion(classLoader);
                        setDevelopmentVersion();
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "IOException occured", e);
                        version.set("unknown");
                    }
                    result = version.get();
                }
            }
        }
        return result;
    }

    private void setDevelopmentVersion() {
        if (version.get() == null) {
            version.set("dev");
        }
    }

    private void setMinioClientJavaVersion(ClassLoader classLoader) throws IOException {
        if (classLoader != null) {
            Enumeration<URL> resources = classLoader.getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                for (Object k : manifest.getMainAttributes().keySet()) {
                    String versionString = "Implementation-Version";
                    if (k.toString().equals(versionString)) {
                        version.set(manifest.getMainAttributes().getValue((Attributes.Name) k));
                    }
                }
            }
        }
    }
}
