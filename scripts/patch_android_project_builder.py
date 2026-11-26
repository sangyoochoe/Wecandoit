from pathlib import Path
import shutil
import re

root = Path('craft-anything-android/app/src/main/java/ai/storyteller/craftanything')
main = root / 'MainActivity.kt'
s = main.read_text(encoding='utf-8')
shutil.copyfile('scripts/ProjectBuilder.kt', root / 'ProjectBuilder.kt')

# ProjectBuilder.kt embeds shell scripts in Kotlin raw strings. Escape shell
# dollar signs only in those two literals so Kotlin does not treat $VAR or
# $(...) as Kotlin string templates while preserving the generated shell.
builder = root / 'ProjectBuilder.kt'
builder_source = builder.read_text(encoding='utf-8')
def escape_shell_dollars(match):
    return match.group(1) + match.group(2).replace('$', "${'$'}") + match.group(3)
builder_source, escaped_literals = re.subn(
    r'((?:val script|val workflow) = """)(.*?)(""")',
    escape_shell_dollars,
    builder_source,
    flags=re.DOTALL,
)
if escaped_literals != 2:
    raise SystemExit(f"Expected 2 embedded shell-script literals, found {escaped_literals}")
builder.write_text(builder_source, encoding='utf-8')

shutil.copyfile('scripts/CraftWorkflow.kt', root / 'CraftWorkflow.kt')