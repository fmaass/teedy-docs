package com.sismics.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Classes scanner.
 */
public class ClasspathScanner<T> {
    private static final Logger log = LoggerFactory.getLogger(ClasspathScanner.class);

    @SuppressWarnings("unchecked")
    public List<Class<T>> findClasses(Class<T> topClass, String pkg) {
        List<Class<T>> classes = new ArrayList<>();
        try {
            for (ServiceLoader.Provider<T> provider : ServiceLoader.load(topClass).stream().toList()) {
                classes.add((Class<T>) provider.type());
            }
        } catch (Exception e) {
            log.error("Error discovering service providers for {}", topClass.getSimpleName(), e);
        }

        classes.sort((o1, o2) -> {
            Priority priority1 = o1.getDeclaredAnnotation(Priority.class);
            Priority priority2 = o2.getDeclaredAnnotation(Priority.class);
            return Integer.compare(priority1 == null ? Integer.MAX_VALUE : priority1.value(),
                    priority2 == null ? Integer.MAX_VALUE : priority2.value());
        });

        log.info("Found {} classes for {}", classes.size(), topClass.getSimpleName());
        return classes;
    }

    /**
     * Classpath scanning priority.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Priority {
        int value() default Integer.MAX_VALUE;
    }
}
