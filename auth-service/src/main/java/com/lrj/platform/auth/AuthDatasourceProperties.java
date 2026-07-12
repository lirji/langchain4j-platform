package com.lrj.platform.auth;

/** auth-service JDBC 数据源属性（前缀 {@code app.auth.datasource.*}）；仅 {@code AUTH_STORE=jdbc} 时生效。 */
public class AuthDatasourceProperties {

    private String url = "jdbc:mysql://localhost:3306/auth?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true";
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    private String username = "root";
    private String password = "";
    private int maximumPoolSize = 8;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
}
