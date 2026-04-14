package com.example.gateway.support;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Shared Testcontainers Postgres initializer: starts a single container for the JVM,
 * runs the Liquibase changelog against it via JDBC (JDBC only exists on the test
 * classpath), then wires the gateway's R2DBC and OAuth2 client properties to point at
 * the container.
 */
public class PostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String IMAGE = "postgres:16-alpine";
    private static final String DATABASE = "gateway";
    private static final String ADMIN_USER = "gateway_admin";
    private static final String ADMIN_PASSWORD = "adminpw";
    private static final String READER_USER = "gateway_reader";
    private static final String READER_PASSWORD = "readerpw";

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_PASSWORD)
                    .withReuse(true);

    static {
        CONTAINER.start();
        applyLiquibase();
        createReaderRole();
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                CONTAINER.getHost(),
                CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                DATABASE);

        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(applicationContext,
                "spring.r2dbc.url=" + r2dbcUrl,
                "spring.r2dbc.username=" + READER_USER,
                "spring.r2dbc.password=" + READER_PASSWORD,
                // The `local` profile's application-local.yml configures
                // spring.sql.init to run the H2 schema script at startup. In
                // these tests we're on a Testcontainers Postgres that already
                // has the schema + seed applied by Liquibase above, so suppress
                // sql.init to avoid DDL errors as the reader user.
                "spring.sql.init.mode=never");
        // OAuth2 client wiring comes from OAuth2ClientConfig's @Profile("local")
        // bean — see @ActiveProfiles("local") on the integration test classes.
    }

    private static void applyLiquibase() {
        try (Connection c = DriverManager.getConnection(CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(), CONTAINER.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            File changelogDir = new File("liquibase/changelog").getAbsoluteFile();
            try (Liquibase liquibase = new Liquibase(
                    "db.changelog-master.xml",
                    new DirectoryResourceAccessor(changelogDir),
                    database)) {
                liquibase.setChangeLogParameter("gateway_reader_password", READER_PASSWORD);
                liquibase.update("");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply Liquibase changelog to Testcontainer", e);
        }
    }

    private static void createReaderRole() {
        // The changelog's 002-create-reader-role runs as gateway_admin (the container's
        // superuser), which creates the role. Nothing extra to do here, but this method
        // exists as a hook in case future tests need to tweak permissions.
        try (Connection c = DriverManager.getConnection(CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement s = c.createStatement()) {
            s.execute("SELECT 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static PostgreSQLContainer<?> container() {
        return CONTAINER;
    }
}
