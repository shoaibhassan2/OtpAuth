import os

# Files to skip content for
SKIP_CONTENT = {'cJSON.c', 'cJSON.h', "DarkMode.h", "darkmode_dynamic.c"}

# File extensions to exclude completely
EXCLUDE_EXTENSIONS = {
    '.o', '.py', '.pyc', '.pyo', '.pyw', '.bat', '.bmp', '.wav', '.png',
    '.ico', '.dll', '.obj', '.exe', '.json', '.mmdb',".gif",".ttf"
}

OUTPUT_FILE = "project_dump.txt"


def write_tree_and_files(root_path, file, prefix=''):
    try:
        entries = sorted(os.listdir(root_path))
    except Exception as e:
        file.write(f"{prefix}[Error opening directory {root_path}: {e}]\n")
        return

    for index, entry in enumerate(entries):
        path = os.path.join(root_path, entry)
        is_last = index == len(entries) - 1
        branch = '└── ' if is_last else '├── '
        file.write(prefix + branch + entry + '\n')

        new_prefix = prefix + ('    ' if is_last else '│   ')

        if os.path.isdir(path):
            # Recurse into subdirectory
            write_tree_and_files(path, file, new_prefix)
        else:
            _, ext = os.path.splitext(entry)
            if ext in EXCLUDE_EXTENSIONS:
                continue
            if entry in SKIP_CONTENT:
                continue
            write_file_content(path, file)


def write_file_content(file_path, file):
    rel_path = os.path.relpath(file_path)
    file.write(f"\n--- File: {rel_path} ---\n")
    try:
        with open(file_path, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
            file.write(content)
    except Exception as e:
        file.write(f"[Error reading {file_path}: {e}]\n")
    file.write(f"\n--- End of {rel_path} ---\n\n")


if __name__ == '__main__':
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as out:
        out.write("Project Structure and File Contents:\n\n")
        write_tree_and_files(os.getcwd(), out)

    print(f"✅ Project structure and file contents saved to '{OUTPUT_FILE}'")
