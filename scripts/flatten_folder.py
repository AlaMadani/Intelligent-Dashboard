from pathlib import Path
import os

# ============================================================
# CONFIGURATION
# ============================================================

BACK_FOLDER = Path(r"C:\Users\alaa-eddine.madani-e\Desktop\my pfe\pfe-back")
FRONT_FOLDER = Path(r"C:\Users\alaa-eddine.madani-e\Desktop\my pfe\front-pfe")
OUTPUT_FOLDER = Path(r"C:\Users\alaa-eddine.madani-e\Desktop\my pfe\generated_txt")

BACK_OUTPUT_FILE = OUTPUT_FOLDER / "pfe-back_all_contents.txt"
FRONT_OUTPUT_FILE = OUTPUT_FOLDER / "front-pfe_all_contents.txt"
STRUCTURE_OUTPUT_FILE = OUTPUT_FOLDER / "structure_and_unreadable_files.txt"

MAX_OUTPUT_SIZE_BYTES = 3 * 1024 * 1024  # 3 MB
SEPARATOR = "\n" + "-" * 80 + "\n"

# ============================================================
# SOURCE / CONFIG ONLY FILTERS
# ============================================================

# Directories to exclude completely
BACK_EXCLUDED_DIRS = {
    "external libraries",
    "target",
    "build",
    "out",
    "bin",
    ".idea",
    ".git",
    ".gradle",
    ".mvn",
    ".settings",
    "__pycache__",
    ".classpath",
    ".project",
    ".vscode",
    ".cache",
    "coverage",
    "dist",
}

FRONT_EXCLUDED_DIRS = {
    "node_modules",
    "libraries",
    "dist",
    ".quasar",
    ".idea",
    ".git",
    "coverage",
    ".cache",
    "__pycache__",
    ".vscode",
    "build",
    "out",
}

# Allowed source code extensions
ALLOWED_SOURCE_EXTENSIONS = {
    ".java",
    ".kt",
    ".groovy",
    ".py",
    ".js",
    ".jsx",
    ".ts",
    ".tsx",
    ".vue",
    ".css",
    ".scss",
    ".sass",
    ".less",
    ".html",
    ".htm",
    ".sql",
    ".ps1",
    ".sh",
    ".bat",
    ".cmd",
}

# Allowed config / project file extensions
ALLOWED_CONFIG_EXTENSIONS = {
    ".xml",
    ".yml",
    ".yaml",
    ".properties",
    ".json",
    ".toml",
    ".ini",
    ".cfg",
    ".conf",
}

# Allowed exact filenames (important files with no useful extension or special names)
ALLOWED_EXACT_FILENAMES = {
    "pom.xml",
    "mvnw",
    "mvnw.cmd",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "package.json",
    "tsconfig.json",
    "tsconfig.app.json",
    "tsconfig.node.json",
    "quasar.config.js",
    "quasar.config.ts",
    "vite.config.js",
    "vite.config.ts",
    "postcss.config.js",
    "postcss.config.cjs",
    "babel.config.js",
    "babel.config.cjs",
    "Dockerfile",
    "docker-compose.yml",
    "docker-compose.yaml",
    ".gitignore",
    ".dockerignore",
    ".editorconfig",
    ".prettierrc",
    ".prettierrc.json",
    ".prettierrc.js",
    ".eslintrc",
    ".eslintrc.json",
    ".eslintrc.js",
    ".eslintignore",
    ".env",
    ".env.example",
    ".env.local",
    ".env.development",
    ".env.production",
    ".env.test",
    ".npmrc",
    ".browserslistrc",
}

# Explicitly skipped files even if text-based
SKIP_EXACT_FILENAMES = {
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "pnpm-lock.yml",
    "bun.lockb",
}

# Encodings to try
ENCODINGS_TO_TRY = ["utf-8", "utf-8-sig", "cp1252", "latin-1"]


# ============================================================
# HELPERS
# ============================================================

def normalize_name(name: str) -> str:
    return name.strip().lower()


def is_under(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except Exception:
        return False


def should_exclude_path(path: Path, project_type: str) -> bool:
    """
    Exclude noisy/generated/vendor folders and anything inside OUTPUT_FOLDER.
    """
    if is_under(path, OUTPUT_FOLDER):
        return True

    all_parts = [normalize_name(part) for part in path.parts]

    if project_type == "back":
        return any(part in BACK_EXCLUDED_DIRS for part in all_parts)

    if project_type == "front":
        return any(part in FRONT_EXCLUDED_DIRS for part in all_parts)

    return False


def is_allowed_file(path: Path) -> bool:
    """
    Keep only source code + configuration/project files.
    """
    name = path.name
    lower_name = normalize_name(name)
    suffix = path.suffix.lower()

    # Skip explicit noisy files
    if lower_name in {normalize_name(x) for x in SKIP_EXACT_FILENAMES}:
        return False

    # Skip minified frontend bundles
    if lower_name.endswith(".min.js") or lower_name.endswith(".min.css"):
        return False

    # Exact allowed filenames
    if lower_name in {normalize_name(x) for x in ALLOWED_EXACT_FILENAMES}:
        return True

    # Extension-based allowlist
    if suffix in ALLOWED_SOURCE_EXTENSIONS:
        return True

    if suffix in ALLOWED_CONFIG_EXTENSIONS:
        return True

    return False


def read_text_file(path: Path):
    """
    Try reading a text file using several encodings.
    Returns: (content, error)
    """
    for enc in ENCODINGS_TO_TRY:
        try:
            return path.read_text(encoding=enc), None
        except UnicodeDecodeError:
            continue
        except Exception as e:
            return None, str(e)

    return None, "Could not decode file with tried encodings"


def format_file_block(file_path: Path, content: str) -> str:
    block = []
    block.append(SEPARATOR)
    block.append(f"FILE PATH: {file_path}\n")
    block.append(f"FILE NAME: {file_path.name}\n")
    block.append(SEPARATOR)
    block.append(content)
    if not content.endswith("\n"):
        block.append("\n")
    return "".join(block)


# ============================================================
# CONTENT EXTRACTION WITH 3MB LIMIT
# ============================================================

def collect_files_content(root: Path, output_txt: Path, unreadable: list, skipped_by_size: list, project_type: str):
    """
    Extract only source/config files.
    Keep output <= 3MB.
    """
    header = (
        f"ROOT SCANNED: {root}\n"
        f"PROJECT TYPE: {project_type}\n"
        "CONTENT POLICY: source code + configuration files only\n"
        f"MAX OUTPUT SIZE: {MAX_OUTPUT_SIZE_BYTES} bytes\n"
    )

    current_size = len(header.encode("utf-8"))

    with output_txt.open("w", encoding="utf-8") as out:
        out.write(header)

        for current_root, dirs, files in os.walk(root, topdown=True):
            current_root_path = Path(current_root)

            # prune excluded dirs
            dirs[:] = sorted(
                [d for d in dirs if not should_exclude_path(current_root_path / d, project_type)],
                key=str.lower
            )

            for filename in sorted(files, key=str.lower):
                file_path = current_root_path / filename

                if should_exclude_path(file_path, project_type):
                    continue

                if not is_allowed_file(file_path):
                    continue

                content, error = read_text_file(file_path)
                if error:
                    unreadable.append((str(file_path), error))
                    continue

                block = format_file_block(file_path, content)
                block_size = len(block.encode("utf-8"))

                if current_size + block_size > MAX_OUTPUT_SIZE_BYTES:
                    skipped_by_size.append(
                        (
                            str(file_path),
                            f"Skipped because adding it would exceed the 3MB limit of {output_txt.name}"
                        )
                    )
                    continue

                out.write(block)
                current_size += block_size


# ============================================================
# TREE / STRUCTURE
# ============================================================

def build_tree_lines(root: Path, project_type: str, prefix: str = ""):
    """
    Build a tree that shows only relevant directories/files:
    - excludes noisy folders
    - includes only allowed source/config files
    - includes only directories that contain visible descendants
    """
    lines = []

    try:
        entries = list(root.iterdir())
    except Exception as e:
        lines.append(prefix + f"[ERROR OPENING DIRECTORY] {root} ({e})")
        return lines

    visible_entries = []

    for entry in entries:
        if should_exclude_path(entry, project_type):
            continue

        if entry.is_file():
            if is_allowed_file(entry):
                visible_entries.append(entry)
        elif entry.is_dir():
            child_lines = build_tree_lines(entry, project_type, "")
            if child_lines:  # keep only directories that eventually contain relevant files
                visible_entries.append(entry)

    visible_entries.sort(key=lambda p: (p.is_file(), p.name.lower()))

    for index, entry in enumerate(visible_entries):
        connector = "└── " if index == len(visible_entries) - 1 else "├── "
        lines.append(prefix + connector + entry.name)

        if entry.is_dir():
            extension = "    " if index == len(visible_entries) - 1 else "│   "
            child_lines = build_tree_lines(entry, project_type, prefix + extension)
            lines.extend(child_lines)

    return lines


def write_structure_file(output_file: Path, unreadable_files: list, skipped_by_size_files: list):
    with output_file.open("w", encoding="utf-8") as f:
        f.write("===== PROJECT STRUCTURE =====\n\n")
        f.write("NOTE: This structure includes only source code and configuration files.\n")
        f.write("NOTE: Noisy folders such as node_modules, External Libraries, target, build, dist, etc. are omitted.\n\n")

        base = BACK_FOLDER.parent
        f.write(f"{base.name}\n")

        if BACK_FOLDER.exists():
            f.write(f"├── {BACK_FOLDER.name}\n")
            back_lines = build_tree_lines(BACK_FOLDER, "back", "│   ")
            for line in back_lines:
                f.write(line + "\n")
        else:
            f.write(f"├── {BACK_FOLDER.name} [NOT FOUND]\n")

        if FRONT_FOLDER.exists():
            f.write(f"└── {FRONT_FOLDER.name}\n")
            front_lines = build_tree_lines(FRONT_FOLDER, "front", "    ")
            for line in front_lines:
                f.write(line + "\n")
        else:
            f.write(f"└── {FRONT_FOLDER.name} [NOT FOUND]\n")

        f.write("\n\n===== FILES THAT COULD NOT BE READ =====\n\n")
        if not unreadable_files:
            f.write("All included source/config files were read successfully.\n")
        else:
            for path_str, reason in unreadable_files:
                f.write(f"PATH: {path_str}\n")
                f.write(f"REASON: {reason}\n")
                f.write("-" * 80 + "\n")

        f.write("\n\n===== FILES SKIPPED BECAUSE OF THE 3MB LIMIT =====\n\n")
        if not skipped_by_size_files:
            f.write("No files were skipped بسبب the 3MB size limit.\n")
        else:
            for path_str, reason in skipped_by_size_files:
                f.write(f"PATH: {path_str}\n")
                f.write(f"REASON: {reason}\n")
                f.write("-" * 80 + "\n")


# ============================================================
# MAIN
# ============================================================

def main():
    OUTPUT_FOLDER.mkdir(parents=True, exist_ok=True)

    unreadable_files = []
    skipped_by_size_files = []

    if not BACK_FOLDER.exists():
        print(f"❌ Backend folder not found:\n{BACK_FOLDER}")
        return

    if not FRONT_FOLDER.exists():
        print(f"❌ Frontend folder not found:\n{FRONT_FOLDER}")
        return

    print("Scanning backend (source code + config only)...")
    collect_files_content(
        root=BACK_FOLDER,
        output_txt=BACK_OUTPUT_FILE,
        unreadable=unreadable_files,
        skipped_by_size=skipped_by_size_files,
        project_type="back"
    )

    print("Scanning frontend (source code + config only)...")
    collect_files_content(
        root=FRONT_FOLDER,
        output_txt=FRONT_OUTPUT_FILE,
        unreadable=unreadable_files,
        skipped_by_size=skipped_by_size_files,
        project_type="front"
    )

    print("Writing structure and report file...")
    write_structure_file(
        output_file=STRUCTURE_OUTPUT_FILE,
        unreadable_files=unreadable_files,
        skipped_by_size_files=skipped_by_size_files
    )

    print("\n✅ Done.")
    print(f"Output folder: {OUTPUT_FOLDER}")
    print(f"- {BACK_OUTPUT_FILE.name}")
    print(f"- {FRONT_OUTPUT_FILE.name}")
    print(f"- {STRUCTURE_OUTPUT_FILE.name}")
    print(f"\nEach content file is capped at {MAX_OUTPUT_SIZE_BYTES} bytes (~3MB).")


if __name__ == "__main__":
    main()