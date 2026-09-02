package dev.gamersden.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Loads {@code db/demo/demo-seed.sql} on a {@code dev} start (TASK B22) — the furniture a
 * developer needs to open a screen and see something: a floor, a menu, members, an open till, a
 * paid booking and a waiting play ticket.
 *
 * <p>{@code @Profile("dev")} is the whole safety story, twice over: the venue and cloud profiles
 * never construct this bean, and the script itself does nothing on a database that already has
 * stations, so a developer who restarts twice does not get two floors. The schema and the rows a
 * venue genuinely cannot boot without are Flyway's business (V001, V003) and are not touched here
 * — a demo is not a migration, and putting it in one would ship it to the cafe.
 */
@Component
@Profile("dev")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String SCRIPT = "db/demo/demo-seed.sql";

    private final DataSource dataSource;

    public DemoDataSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // One statement: the script is a single DO block, so the usual ";" splitting would
            // cut it in half. EOF as the separator hands it over whole.
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SCRIPT), java.nio.charset.StandardCharsets.UTF_8),
                    false, false,
                    ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
        log.info("dev demo seed applied from {}", SCRIPT);
    }
}
