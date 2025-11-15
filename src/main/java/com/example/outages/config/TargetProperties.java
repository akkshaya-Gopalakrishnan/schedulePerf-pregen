package com.example.outages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "target")
public class TargetProperties {
    private String endpoint;
    private Auth auth = new Auth();
    public static class Auth {
        private String type = "bearer"; // bearer|header|none
        private String token;
        private String headerName = "X-API-Key";
        private String headerValue;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getHeaderValue() { return headerValue; }
        public void setHeaderValue(String headerValue) { this.headerValue = headerValue; }
    }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
}
