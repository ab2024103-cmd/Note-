package com.notepadpro.shared.platform

import app.cash.sqldelight.db.SqlDriver

/** Filename shared by Android and desktop for the SQLite database. */
const val DB_FILE_NAME = "notepad-pro.sqlite3"

expect fun createDatabaseDriver(): SqlDriver
