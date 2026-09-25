# Bei File Tidying manual test

This directory is an isolated test area. Its paths contain only ASCII characters, and it does not use the real folders from the project root configuration.

## Test data

The sensed root is `sense-root/` and the target folder is `sense-root/Inbox/`.

Existing destination folders:

- `Pictures/`
- `Documents/`
- `Work/`
- `Archives/`

The target contains a few deliberately misplaced files:

- `Inbox/trip-photo.png` — image in the inbox
- `Inbox/meeting-notes.md` — project notes in the inbox
- `Inbox/Temp/invoice.pdf` — invoice in an unsuitable temporary folder
- `Inbox/Unsorted/project-backup.zip` — backup in an unsorted folder
- `Inbox/.DS_Store` — hidden metadata file that should be ignored

## Run in IntelliJ IDEA

In this workspace, the project root `application.properties` already points to this test area and keeps the existing third-party API settings. If you clone the repository elsewhere, copy `application.properties.example` to `application.properties` and add your API key.

1. Open **Run > Edit Configurations** and create or edit an **Application** configuration.
2. Set the main class to `com.example.tidying.FileTidyingAssistant`.
3. Set **Working directory** to `$PROJECT_DIR$`.
4. Use a Java 17 or newer runtime and run the configuration.
5. Review the printed plan. Enter the exact text `APPLY` only when the plan is acceptable.

The default tree depth is 3. The four files are sent in one batch with the current project settings. To undo the last applied run without calling AI again, run `--undo-last` or enter `UNDO` at the prompt. Undo restores the source files and removes newly created destination folders when they are empty.

The application reads the project root configuration and only scans `manual-test/sense-root/Inbox`, so the real download folders are not involved.

After applying, the usual result is:

- `trip-photo.png` under `Pictures/`
- `meeting-notes.md` under `Work/` or another AI-selected suitable folder
- `invoice.pdf` under `Documents/`
- `project-backup.zip` under `Archives/`
- `.DS_Store` still under `Inbox/`

The AI may choose a different sensible destination. Check the printed plan before typing `APPLY`.
