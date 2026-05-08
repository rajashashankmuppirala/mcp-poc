package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Session configuration properties.
 */
@Configuration
@ConfigurationProperties(prefix = "session")
public class SessionConfig {

    private int timeoutMinutes = 30;
    private int maxTurns = 100;
    private int contextWindowSize = 8;
    private Storage storage = new Storage();
    private Cookie cookie = new Cookie();

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(int contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public void setCookie(Cookie cookie) {
        this.cookie = cookie;
    }

    public Duration getTimeout() {
        return Duration.ofMinutes(timeoutMinutes);
    }

    public static class Storage {
        private String type = "in-memory";  // "in-memory" or "redis"

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public static class Cookie {
        private String name = "session-id";
        private boolean httpOnly = true;
        private boolean secure = false;
        private String sameSite = "Strict";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isHttpOnly() {
            return httpOnly;
        }

        public void setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }
    }
}
