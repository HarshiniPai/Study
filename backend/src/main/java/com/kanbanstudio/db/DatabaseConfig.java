package com.kanbanstudio.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration
class DatabaseConfig {

    @Bean
    DataSource dataSource(@Value("${app.database-path}") String databasePath) throws IOException {
        Path path = Path.of(databasePath).toAbsolutePath();
        Files.createDirectories(path.getParent());

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        // Take the write lock when a transaction starts, so read-then-write saves cannot deadlock.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + path);
        return dataSource;
    }
}
