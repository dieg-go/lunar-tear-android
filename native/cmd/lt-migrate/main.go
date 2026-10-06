// Command lt-migrate applies the lunar-tear SQLite schema migrations.
//
// The upstream game server does NOT migrate on startup: database.Open() only
// opens the file and sets pragmas, and server/entrypoint.sh shells out to the
// goose CLI. An Android app cannot rely on a goose CLI being present, so this
// tool embeds the migration SQL and runs goose as a library.
//
// The migrations directory is populated at build time by tools/build-native.ps1
// from $LUNAR_TEAR_SRC/server/migrations (the upstream tree is never modified).
package main

import (
	"database/sql"
	"embed"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"

	"github.com/pressly/goose/v3"
	_ "modernc.org/sqlite"
)

//go:embed all:migrations
var migrationsFS embed.FS

func main() {
	dbPath := flag.String("db", "db/game.db", "SQLite database path")
	mode := flag.String("mode", "up", "up | status | version | down-to")
	to := flag.Int64("to", 0, "target version for --mode=down-to")
	dir := flag.String("dir", "", "use migrations from this directory instead of the embedded copy (dev/tests)")
	quiet := flag.Bool("quiet", false, "only print the final status line")
	flag.Parse()

	migrationsDir := "migrations"
	if *dir != "" {
		// goose falls back to os.DirFS(".") when baseFS is nil, and os.DirFS
		// cannot resolve an absolute Windows path, so hand it the directory.
		goose.SetBaseFS(os.DirFS(*dir))
		migrationsDir = "."
	} else {
		goose.SetBaseFS(migrationsFS)
	}

	if dir := filepath.Dir(*dbPath); dir != "." && dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			log.Fatalf("lt-migrate: create db directory %q: %v", dir, err)
		}
	}

	db, err := sql.Open("sqlite", *dbPath)
	if err != nil {
		log.Fatalf("lt-migrate: open %q: %v", *dbPath, err)
	}
	defer db.Close()

	goose.SetLogger(goose.NopLogger())
	if err := goose.SetDialect("sqlite3"); err != nil {
		log.Fatalf("lt-migrate: set dialect: %v", err)
	}

	switch *mode {
	case "up":
		if err := goose.Up(db, migrationsDir); err != nil {
			log.Fatalf("lt-migrate: up: %v", err)
		}
	case "status":
		if err := goose.Status(db, migrationsDir); err != nil {
			log.Fatalf("lt-migrate: status: %v", err)
		}
		return
	case "version":
		v, err := goose.GetDBVersion(db)
		if err != nil {
			log.Fatalf("lt-migrate: version: %v", err)
		}
		fmt.Printf("db version: %d\n", v)
		return
	case "down-to":
		if err := goose.DownTo(db, migrationsDir, *to); err != nil {
			log.Fatalf("lt-migrate: down-to: %v", err)
		}
	default:
		log.Fatalf("lt-migrate: unknown --mode %q", *mode)
	}

	v, err := goose.GetDBVersion(db)
	if err != nil {
		log.Fatalf("lt-migrate: version: %v", err)
	}
	if *quiet {
		fmt.Printf("OK %d\n", v)
		return
	}
	fmt.Printf("lt-migrate: database %s is at version %d\n", *dbPath, v)
}
