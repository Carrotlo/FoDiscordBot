package me.foesio.foDiscordBot.api;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface DatabaseAction<T> {

    T execute(Connection connection) throws SQLException;
}
